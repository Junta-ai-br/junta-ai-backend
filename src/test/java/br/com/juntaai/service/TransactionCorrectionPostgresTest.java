package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Disposable PostgreSQL only; AI transport is mocked, financial commit/receipts are real. */
@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
class TransactionCorrectionPostgresTest extends AbstractIntegrationTest {
    @Autowired ActionExecutionService executions;
    @Autowired ConversationService conversations;
    @SpyBean ConversationTurnStore turns;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired br.com.juntaai.repository.TransactionRepository transactionRepository;
    @Autowired TransactionService transactionService;
    @Autowired JdbcTemplate jdbc;
    @MockBean AiClient ai;
    UUID user, conversation, message, transaction, originalCategory, newCategory;

    @BeforeEach void seed() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user = UUID.randomUUID(); conversation = UUID.randomUUID(); message = UUID.randomUUID();
        transaction = UUID.randomUUID(); originalCategory = UUID.randomUUID(); newCategory = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?, 'Synthetic',?)", user, user + "@test.invalid");
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','sim')", message, conversation);
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Original','EXPENSE')", originalCategory, user);
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'NewCategory','EXPENSE')", newCategory, user);
        jdbc.update("""
            insert into transactions(id,user_id,category_id,type,description,amount,transaction_date)
            values(?,?,?,'EXPENSE','preserved',100,'2026-10-01')
            """, transaction, user, originalCategory);
    }

    Instant revision() {
        return jdbc.queryForObject("select updated_at from transactions where id=?", java.sql.Timestamp.class, transaction).toInstant();
    }
    ParsedFinancialData payload(UUID id, Instant revision, String category, Operation type, String amount) {
        return new ParsedFinancialData(new BigDecimal(amount), "preserved", category, null,
            "2026-10-01", null, type, TemporalStatus.REALIZED, null, new CorrectionTarget(id, revision));
    }
    String proposal(ParsedFinancialData p) throws Exception {
        var pending = new PendingOperation(PendingState.CORRECTION, Operation.CORRECTION, p, List.of("confirmation"), p.correction_target().id().toString());
        String state = objectMapper.writeValueAsString(pending);
        jdbc.update("update conversations set pending_state=? where id=?", state, conversation);
        return state;
    }
    ActionRequest action(ParsedFinancialData p) {
        return new ActionRequest(UUID.randomUUID().toString(), Operation.CORRECTION, p, "authoritative_state", Instant.now().plusSeconds(600));
    }
    ActionExecutionService.Receipt execute(ActionRequest action, String state) {
        return executions.execute(user, conversation, message, "synthetic", action, state);
    }

    @Test void correctionUpdatesOnlyAuthorizedFieldsAndReplaysReceipt() throws Exception {
        var p = payload(transaction, revision(), "NewCategory", Operation.EXPENSE, "80.25");
        var state = proposal(p); var action = action(p);
        assertThat(execute(action, state).confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        assertThat(execute(action, state).confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        var row = jdbc.queryForMap("select * from transactions where id=?", transaction);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("80.25");
        assertThat(row.get("category_id")).isEqualTo(newCategory);
        assertThat(row.get("type")).isEqualTo("EXPENSE");
        assertThat(row.get("description")).isEqualTo("preserved");
        assertThat(row.get("transaction_date").toString()).isEqualTo("2026-10-01");
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?", Integer.class, user)).isEqualTo(1);
    }

    @Test void incomeAmountCorrectionPreservesOriginalType() throws Exception {
        jdbc.update("update transactions set type='INCOME' where id=?", transaction);
        jdbc.update("update categories set type='INCOME' where user_id=?", user);
        var p = payload(transaction, revision(), "NewCategory", Operation.INCOME, "50");
        var result = execute(action(p), proposal(p));
        assertThat(result.confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        assertThat(jdbc.queryForObject("select type from transactions where id=?", String.class, transaction)).isEqualTo("INCOME");
        assertThat(jdbc.queryForObject("select amount from transactions where id=?", BigDecimal.class, transaction)).isEqualByComparingTo("50");
    }

    @Test void categoryOnlyCorrectionPreservesAmount() throws Exception {
        var p = payload(transaction, revision(), "NewCategory", Operation.EXPENSE, "100");
        assertThat(execute(action(p), proposal(p)).confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        assertThat(jdbc.queryForObject("select amount from transactions where id=?", BigDecimal.class, transaction)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select category_id from transactions where id=?", UUID.class, transaction)).isEqualTo(newCategory);
    }

    @Test void staleFirstLevelCacheCannotHideRevisionChange() {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
            var before = transactionRepository.findByIdAndUserId(transaction, user).orElseThrow();
            Instant old = before.getUpdatedAt();
            jdbc.update("update transactions set amount=90, updated_at=updated_at+interval '1 second' where id=?", transaction);
            assertThat(transactionService.correct(user, transaction, old, br.com.juntaai.entity.TransactionType.EXPENSE,
                    newCategory, new BigDecimal("80"))).isNull();
            return null;
        });
        assertThat(jdbc.queryForObject("select amount from transactions where id=?", BigDecimal.class, transaction)).isEqualByComparingTo("90");
    }

    @Test void snapshotFailureRollsBackUpdateAndReceiptTogether() throws Exception {
        var p = payload(transaction, revision(), "Original", Operation.EXPENSE, "80");
        var state = proposal(p);
        doThrow(new br.com.juntaai.exception.BusinessRuleException("snapshot_failure")).when(turns).buildFinancialSnapshot(user);
        assertThatThrownBy(() -> execute(action(p), state)).hasMessage("snapshot_failure");
        assertThat(jdbc.queryForObject("select amount from transactions where id=?", BigDecimal.class, transaction)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?", Integer.class, user)).isZero();
    }

    @Test void changedTransactionRejectsStaleProposal() throws Exception {
        var p = payload(transaction, revision(), "Original", Operation.EXPENSE, "80");
        var state = proposal(p);
        jdbc.update("update transactions set amount=90, updated_at=updated_at+interval '1 second' where id=?", transaction);
        assertThat(execute(action(p), state).confirmation().reason()).isEqualTo("transaction_missing_or_changed");
        assertThat(jdbc.queryForObject("select amount from transactions where id=?", BigDecimal.class, transaction)).isEqualByComparingTo("90");
    }

    @Test void nonexistentOrForeignTransactionCannotBeCorrected() throws Exception {
        UUID other = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,'Other',?)", other, other + "@test.invalid");
        jdbc.update("update transactions set user_id=? where id=?", other, transaction);
        var p = payload(transaction, revision(), "Original", Operation.EXPENSE, "80");
        assertThat(execute(action(p), proposal(p)).confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        jdbc.update("update action_executions set composition_pending=false where user_id=?", user);
        p = payload(UUID.randomUUID(), Instant.now(), "Original", Operation.EXPENSE, "80");
        assertThat(execute(action(p), proposal(p)).confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
    }

    @Test void categoryTypeAndTransactionTypeChangesAreRejected() throws Exception {
        jdbc.update("update categories set type='INCOME' where id=?", newCategory);
        var p = payload(transaction, revision(), "NewCategory", Operation.EXPENSE, "80");
        assertThat(execute(action(p), proposal(p)).confirmation().reason()).isEqualTo("category_ineligible");
        jdbc.update("update action_executions set composition_pending=false where user_id=?", user);
        p = payload(transaction, revision(), "NewCategory", Operation.INCOME, "80");
        assertThat(execute(action(p), proposal(p)).confirmation().reason()).isEqualTo("transaction_missing_or_changed");
    }

    @Test void confirmationMustMatchPersistedProposalAndAffirmativeMessage() throws Exception {
        var p = payload(transaction, revision(), "Original", Operation.EXPENSE, "80");
        var state = proposal(p);
        jdbc.update("update messages set content='não' where id=?", message);
        assertThat(execute(action(p), state).confirmation().reason()).isEqualTo("correction_confirmation_required");
        jdbc.update("update action_executions set composition_pending=false where user_id=?", user);
        jdbc.update("update messages set content='sim' where id=?", message);
        var changed = payload(transaction, revision(), "Original", Operation.EXPENSE, "50");
        assertThat(execute(action(changed), state).confirmation().reason()).isEqualTo("correction_confirmation_required");
        assertThat(jdbc.queryForObject("select amount from transactions where id=?", BigDecimal.class, transaction)).isEqualByComparingTo("100");
    }

    @Test void concurrentDistinctProposalsCannotOverwriteEachOther() throws Exception {
        var p = payload(transaction, revision(), "Original", Operation.EXPENSE, "80");
        var firstState = proposal(p); var firstAction = action(p);
        UUID secondConversation = UUID.randomUUID(), secondMessage = UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id,pending_state) values(?,?,?)", secondConversation, user, firstState);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','sim')", secondMessage, secondConversation);
        var secondAction = action(p);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { barrier.await(); return execute(firstAction, firstState).confirmation().status(); });
            var second = pool.submit(() -> { barrier.await(); return executions.execute(user, secondConversation, secondMessage,
                    "other", secondAction, firstState).confirmation().status(); });
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ExecutionStatus.SUCCESSFUL, ExecutionStatus.REJECTED);
        }
    }

    @Test void repeatedConfirmationMessageUsesExistingMessageReceipt() throws Exception {
        var p = payload(transaction, revision(), "Original", Operation.EXPENSE, "80");
        proposal(p);
        when(ai.process(any())).thenAnswer(inv -> {
            AIServiceRequest request = inv.getArgument(0);
            if (request.execution_confirmation() != null) {
                return new ProcessingResult(request.request_id(), "1", null,
                    new Decision(DecisionType.RESPONSE, "execution_confirmed", List.of()), null, null,
                    new ConversationalResponse("Corrigido.", List.of()), null, null);
            }
            return new ProcessingResult(request.request_id(), "1", null,
                new Decision(DecisionType.ACTION_REQUEST, null, List.of()), action(p), null, null, null, null);
        });
        UUID key = UUID.randomUUID();
        var first = conversations.sendMessage(user, conversation, new ChatMessageRequest("sim"), key);
        var repeat = conversations.sendMessage(user, conversation, new ChatMessageRequest("sim"), key);
        assertThat(first.actionExecuted()).isTrue();
        assertThat(repeat.assistantMessage().id()).isEqualTo(first.assistantMessage().id());
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?", String.class, conversation)).isNull();
        verify(ai, times(2)).process(any());
    }

    @Test void snapshotCarriesOwnedRevisionAndUnambiguousLastRegistration() {
        var state = turns.buildFinancialSnapshot(user);
        assertThat(state.current_state().get("last_transaction_id")).isEqualTo(transaction.toString());
        assertThat(state.realized_records()).anySatisfy(row -> {
            assertThat(row.get("id")).isEqualTo(transaction.toString());
            assertThat(row.get("updated_at")).isNotNull();
        });
    }
}
