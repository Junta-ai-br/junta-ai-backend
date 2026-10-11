package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.exception.*;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, migrations and Spring proxies; only AI transport/snapshot failure is faked. */
@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
class ActionExecutionPostgresTest extends AbstractIntegrationTest {
    @Autowired private ActionExecutionService executions;
    @Autowired private ConversationService conversations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private ConversationTurnStore turns;
    @MockBean private AiClient ai;
    private UUID user, conversation, message;

    @BeforeEach void seed() {
        assertThat(jdbc.getDataSource()).isNotNull();
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        } catch (java.sql.SQLException ex) { throw new AssertionError(ex); }
        user = UUID.randomUUID(); conversation = UUID.randomUUID(); message = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", user, "Synthetic", user + "@test.invalid");
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','synthetic')", message, conversation);
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'TestCategory','EXPENSE')", UUID.randomUUID(), user);
    }

    private ActionRequest action(String id, BigDecimal amount) {
        return new ActionRequest(id, Operation.EXPENSE, new ParsedFinancialData(amount,
                "synthetic", "TestCategory", "hoje", LocalDate.now().toString(), null,
                Operation.EXPENSE, TemporalStatus.REALIZED, null), "authoritative_state", Instant.now().plusSeconds(600));
    }
    private ActionExecutionService.Receipt execute(ActionRequest action) {
        return executions.execute(user, conversation, message, "backend-origin", action, null);
    }
    private int effects() { return jdbc.queryForObject("select count(*) from transactions where user_id=?", Integer.class, user); }
    private int records() { return jdbc.queryForObject("select count(*) from action_executions where user_id=?", Integer.class, user); }
    private boolean pending() { return jdbc.queryForObject("select composition_pending from action_executions where user_id=?", Boolean.class, user); }

    @Test void sameActionRecoversCommittedReceiptWithoutSecondEffect() {
        var action = action(UUID.randomUUID().toString(), new BigDecimal("20.00"));
        var first = execute(action);
        var repeat = executions.execute(user, conversation, message, "later-callback", action, null);
        assertThat(first.confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        assertThat(repeat.confirmation()).isEqualTo(first.confirmation());
        assertThat(repeat.originalRequestId()).isEqualTo("backend-origin");
        assertThat(effects()).isEqualTo(1); assertThat(records()).isEqualTo(1);
    }

    @Test void sameIdChangedContentIsConflict() {
        var original = action(UUID.randomUUID().toString(), new BigDecimal("20.00"));
        execute(original);
        var changed = new ActionRequest(original.action_id(), original.operation(),
                action(original.action_id(), new BigDecimal("999.00")).payload(), original.expected_result(), original.expires_at());
        assertThatThrownBy(() -> execute(changed)).isInstanceOf(BusinessRuleException.class).hasMessage("action_conflict");
        assertThat(effects()).isEqualTo(1);
    }

    @Test void operationAndLifecycleChangesAlsoConflict() {
        var a = action(UUID.randomUUID().toString(), new BigDecimal("20.00")); execute(a);
        for (var different : List.of(
                new ActionRequest(a.action_id(), Operation.INCOME, a.payload(), a.expected_result(), a.expires_at()),
                new ActionRequest(a.action_id(), a.operation(), a.payload(), "changed", a.expires_at()),
                new ActionRequest(a.action_id(), a.operation(), a.payload(), a.expected_result(), a.expires_at().plusSeconds(1)))) {
            assertThatThrownBy(() -> execute(different)).isInstanceOf(BusinessRuleException.class).hasMessage("action_conflict");
        }
        assertThat(effects()).isEqualTo(1);
    }

    private ActionRequest contribution(String id) {
        return new ActionRequest(id, Operation.GOAL_CONTRIBUTION, new ParsedFinancialData(new BigDecimal("10.00"),
                "synthetic", null, null, null, "Target", Operation.GOAL_CONTRIBUTION,
                TemporalStatus.REALIZED, null), "authoritative_state", Instant.now().plusSeconds(600));
    }

    @Test void goalContributionAndReceiptAreAtomicAndIdempotent() {
        UUID goal = UUID.randomUUID();
        jdbc.update("insert into goals(id,user_id,name,target_amount) values(?,?,'Target',100)", goal, user);
        var action = contribution(UUID.randomUUID().toString());
        assertThat(execute(action).confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        execute(action);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?", BigDecimal.class, goal))
                .isEqualByComparingTo("10.00");
        assertThat(records()).isEqualTo(1); assertThat(effects()).isZero();
    }

    @Test void foreignAndAmbiguousGoalNamesCannotSelectUnauthorizedTarget() {
        UUID other = UUID.randomUUID(), otherGoal = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", other, "Other", other + "@test.invalid");
        jdbc.update("insert into goals(id,user_id,name,target_amount) values(?,?,'Target',100)", otherGoal, other);
        assertThat(execute(contribution(UUID.randomUUID().toString())).confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?", BigDecimal.class, otherGoal)).isEqualByComparingTo("0");
        // Finish the negative receipt before proposing a separate ambiguous action.
        when(ai.process(any())).thenAnswer(inv -> response(inv.getArgument(0), "execution_confirmation_not_matching", null));
        conversations.sendMessage(user, conversation, new ChatMessageRequest("retomar"));
        assertThat(pending()).isFalse();
        for (int i = 0; i < 2; i++) jdbc.update("insert into goals(id,user_id,name,target_amount) values(?,?,'Target',100)", UUID.randomUUID(), user);
        assertThat(execute(contribution(UUID.randomUUID().toString())).confirmation().reason()).isEqualTo("goal_unresolved");
        assertThat(jdbc.queryForObject("select sum(current_amount) from goals where user_id=?", BigDecimal.class, user)).isEqualByComparingTo("0");
    }

    @Test void concurrentSameActionHasOneFinancialEffect() throws Exception {
        var action = action(UUID.randomUUID().toString(), new BigDecimal("20.00"));
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<ActionExecutionService.Receipt> job = () -> { barrier.await(10, TimeUnit.SECONDS); return execute(action); };
            var a = pool.submit(job); var b = pool.submit(job);
            assertThat(a.get(20, TimeUnit.SECONDS).confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
            assertThat(b.get(20, TimeUnit.SECONDS).confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
        }
        assertThat(effects()).isEqualTo(1); assertThat(records()).isEqualTo(1);
    }

    @Test void distinctIdsMayLegitimatelyExecuteIdenticalContents() {
        execute(action(UUID.randomUUID().toString(), new BigDecimal("20.00")));
        jdbc.update("update action_executions set composition_pending=false where user_id=?", user);
        execute(action(UUID.randomUUID().toString(), new BigDecimal("20.00")));
        assertThat(effects()).isEqualTo(2); assertThat(records()).isEqualTo(2);
    }

    @Test void anotherUserOrConversationCannotRecoverTheReceipt() {
        var action = action(UUID.randomUUID().toString(), new BigDecimal("20.00")); execute(action);
        UUID otherUser = UUID.randomUUID(), otherConversation = UUID.randomUUID(), otherMessage = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", otherUser, "Other", otherUser + "@test.invalid");
        jdbc.update("insert into conversations(id,user_id) values(?,?)", otherConversation, otherUser);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','synthetic')", otherMessage, otherConversation);
        assertThatThrownBy(() -> executions.pendingReceipt(otherUser, conversation)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> executions.execute(otherUser, otherConversation, otherMessage, "other", action, null))
                .isInstanceOf(BusinessRuleException.class).hasMessage("action_conflict");
        UUID secondConversation = UUID.randomUUID(), secondMessage = UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)", secondConversation, user);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','synthetic')", secondMessage, secondConversation);
        assertThatThrownBy(() -> executions.execute(user, secondConversation, secondMessage, "other", action, null))
                .isInstanceOf(BusinessRuleException.class).hasMessage("action_conflict");
        assertThat(effects()).isEqualTo(1);
    }

    @Test void failureAfterFinancialWriteRollsBackEffectAndReservation() {
        doThrow(new BusinessRuleException("snapshot_failure")).when(turns).buildFinancialSnapshot(user);
        assertThatThrownBy(() -> execute(action(UUID.randomUUID().toString(), new BigDecimal("20.00"))))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(effects()).isZero(); assertThat(records()).isZero();
    }

    @Test void inconsistentPersistedReceiptCannotBePublishedOrReexecuted() {
        var action = action(UUID.randomUUID().toString(), new BigDecimal("20.00")); execute(action);
        String corrupt = "{\"action_id\":\"foreign\",\"status\":\"successful\",\"executed_operation\":\"income\"}";
        jdbc.update("update action_executions set receipt_json=? where action_id=?", corrupt, action.action_id());
        assertThatThrownBy(() -> executions.pendingReceipt(user, conversation))
                .isInstanceOf(BusinessRuleException.class).hasMessage("execution_evidence_invalid");
        assertThatThrownBy(() -> execute(action)).isInstanceOf(BusinessRuleException.class).hasMessage("execution_evidence_invalid");
        assertThat(effects()).isEqualTo(1); assertThat(records()).isEqualTo(1);
    }

    @Test void changedPendingOrAnotherUncomposedReceiptBlocksNewExecution() {
        jdbc.update("update conversations set pending_state='changed' where id=?", conversation);
        assertThatThrownBy(() -> execute(action(UUID.randomUUID().toString(), new BigDecimal("20.00"))))
                .isInstanceOf(BusinessRuleException.class).hasMessage("action_stage_changed");
        assertThat(records()).isZero(); assertThat(effects()).isZero();
        jdbc.update("update conversations set pending_state=null where id=?", conversation);
        execute(action(UUID.randomUUID().toString(), new BigDecimal("20.00")));
        assertThatThrownBy(() -> execute(action(UUID.randomUUID().toString(), new BigDecimal("999.00"))))
                .isInstanceOf(BusinessRuleException.class).hasMessage("action_stage_changed");
        assertThat(records()).isEqualTo(1); assertThat(effects()).isEqualTo(1);
    }

    @Test void databaseDoesNotAllowCommittingAnIncompleteReservation() {
        var tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                insert into action_executions(action_id,user_id,conversation_id,user_message_id,request_id,action_json)
                values(?,?,?,?,?,'{}')
                """, UUID.randomUUID().toString(), user, conversation, message, "origin"))).isInstanceOf(RuntimeException.class);
        assertThat(records()).isZero();
    }

    @Test void expiredNewActionIsRejectedButCommittedReceiptRemainsRecoverable() {
        var fresh = action(UUID.randomUUID().toString(), new BigDecimal("20.00"));
        var expired = new ActionRequest(fresh.action_id(), fresh.operation(), fresh.payload(), fresh.expected_result(), Instant.now().minusSeconds(1));
        assertThat(execute(expired).confirmation().reason()).isEqualTo("action_expired");
        assertThat(effects()).isZero();
        assertThat(execute(expired).confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
    }

    @Test void unsupportedRecurrenceIsNotConsentOrFinancialExecution() {
        var original = action(UUID.randomUUID().toString(), new BigDecimal("20.00"));
        var recurring = new ActionRequest(original.action_id(), Operation.RECURRING_CONFIRMATION,
                original.payload(), original.expected_result(), original.expires_at());
        assertThat(execute(recurring).confirmation().status()).isEqualTo(ExecutionStatus.UNAVAILABLE);
        assertThat(effects()).isZero();
    }

    private ProcessingResult response(AIServiceRequest req, String reason, ActionRequest action) {
        return new ProcessingResult(req.request_id(), req.contract_version(), null,
                new Decision(action == null ? DecisionType.RESPONSE : DecisionType.ACTION_REQUEST, reason, List.of()),
                action, null, new ConversationalResponse("synthetic reply", List.of()), null, null);
    }

    @Test void committedExecutionSurvivesAiTimeoutAndResumesWithoutReexecution() throws Exception {
        PendingOperation prior = new PendingOperation(PendingState.AMOUNT, Operation.EXPENSE,
                new ParsedFinancialData(null, "synthetic", "TestCategory", null, null, null, null, TemporalStatus.UNRESOLVED, null),
                List.of("monetary_value"), null);
        String pending = objectMapper.writeValueAsString(prior);
        jdbc.update("update conversations set pending_state=? where id=?", pending, conversation);
        var action = action(UUID.randomUUID().toString(), new BigDecimal("20.00"));
        var interpretations = new AtomicInteger(); var compositions = new AtomicInteger();
        when(ai.process(any())).thenAnswer(inv -> {
            AIServiceRequest req = inv.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (req.execution_confirmation() == null) { interpretations.incrementAndGet(); return response(req, null, action); }
            // A new JDBC connection sees both rows: the financial proxy committed.
            assertThat(effects()).isEqualTo(1); assertThat(records()).isEqualTo(1);
            assertThat(req.pending_action().action_id()).isEqualTo(action.action_id());
            assertThat(req.execution_confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
            if (compositions.getAndIncrement() == 0) throw new AiServiceUnavailableException("synthetic_timeout");
            return response(req, "execution_confirmed", null);
        });
        var first = conversations.sendMessage(user, conversation, new ChatMessageRequest("synthetic"));
        assertThat(first.actionExecuted()).isTrue(); assertThat(first.assistantMessage().content()).contains("pendente");
        assertThat(pending()).isTrue();
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?", String.class, conversation)).isEqualTo(pending);
        var resumed = conversations.sendMessage(user, conversation, new ChatMessageRequest("retomar"));
        assertThat(resumed.actionExecuted()).isTrue(); assertThat(pending()).isFalse();
        assertThat(interpretations.get()).isEqualTo(1); assertThat(compositions.get()).isEqualTo(2);
        assertThat(effects()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?", String.class, conversation)).isNull();
    }

    @Test void lateReceiptOrAiProposedSecondActionDoesNotRepeatExecution() {
        var action = action(UUID.randomUUID().toString(), new BigDecimal("20.00")); execute(action);
        when(ai.process(any())).thenAnswer(inv -> response(inv.getArgument(0), "execution_confirmation_stale", null));
        var late = conversations.sendMessage(user, conversation, new ChatMessageRequest("retomar"));
        assertThat(late.actionExecuted()).isTrue(); assertThat(pending()).isTrue(); assertThat(effects()).isEqualTo(1);
        doAnswer(inv -> response(inv.getArgument(0), null,
                action(UUID.randomUUID().toString(), new BigDecimal("999.00")))).when(ai).process(any());
        conversations.sendMessage(user, conversation, new ChatMessageRequest("retomar"));
        assertThat(pending()).isTrue(); assertThat(effects()).isEqualTo(1);
    }

    @Test void responseWithoutNewPendingStateDoesNotEraseExistingPending() throws Exception {
        PendingOperation prior = new PendingOperation(PendingState.AMOUNT, Operation.EXPENSE,
                null, List.of("monetary_value"), null);
        String pending = objectMapper.writeValueAsString(prior);
        jdbc.update("update conversations set pending_state=? where id=?", pending, conversation);
        when(ai.process(any())).thenAnswer(inv -> response(inv.getArgument(0), "infrastructure_unavailable", null));
        conversations.sendMessage(user, conversation, new ChatMessageRequest("synthetic"));
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?", String.class, conversation)).isEqualTo(pending);
        assertThat(effects()).isZero();
    }
}
