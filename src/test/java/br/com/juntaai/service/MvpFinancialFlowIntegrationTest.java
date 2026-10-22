package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Opt-in: real AI HTTP transport and real disposable PostgreSQL, without a mocked AiClient. */
@EnabledIfEnvironmentVariable(named = "MVP_REAL_AI_TEST", matches = "true")
@TestPropertySource(properties = "ai.service.base-url=http://127.0.0.1:18081")
class MvpFinancialFlowIntegrationTest extends AbstractIntegrationTest {
    @Autowired private ConversationService conversations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiClient ai;
    @Autowired private ConversationTurnStore turns;
    @Autowired private ActionExecutionService executions;
    private UUID user, conversation;

    @BeforeEach void seed() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", user, "Synthetic MVP", user + "@test.invalid");
        for (var category : new String[][]{{"Transporte", "EXPENSE"}, {"Alimentação", "EXPENSE"}, {"Salário", "INCOME"}}) {
            jdbc.update("insert into categories(id,user_id,name,type) values(?,?,?,?)", UUID.randomUUID(), user, category[0], category[1]);
        }
        conversation = conversations.createConversation(user, "Synthetic MVP").id();
    }

    private int effects() {
        return jdbc.queryForObject("select count(*) from transactions where user_id=?", Integer.class, user);
    }

    @Test void knownCategoriesAndClarificationCommitOnceAndReturnRealReceipts() {
        var key = UUID.randomUUID();
        var input = new ChatMessageRequest("gastei 25 reais no Uber hoje");
        var first = conversations.sendMessage(user, conversation, input, key);
        assertThat(first.actionExecuted()).isTrue();
        assertThat(first.assistantMessage().content()).contains("Registrei a despesa");
        assertThat(effects()).isEqualTo(1);
        conversations.sendMessage(user, conversation, input, key);
        assertThat(effects()).isEqualTo(1);
        var pending = conversations.sendMessage(user, conversation, new ChatMessageRequest("gastei 25 reais"), UUID.randomUUID());
        assertThat(pending.actionExecuted()).isFalse();
        assertThat(pending.assistantMessage().content()).containsIgnoringCase("categoria");
        assertThat(effects()).isEqualTo(1);
        var resolved = conversations.sendMessage(user, conversation, new ChatMessageRequest("alimentação"), UUID.randomUUID());
        assertThat(resolved.actionExecuted()).isTrue();
        assertThat(resolved.assistantMessage().content()).contains("Registrei a despesa");
        assertThat(effects()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?", String.class, conversation)).isNull();
        var income = conversations.sendMessage(user, conversation, new ChatMessageRequest("recebi 1500 de salário hoje"), UUID.randomUUID());
        assertThat(income.actionExecuted()).isTrue();
        assertThat(effects()).isEqualTo(3);
        var supermarket = conversations.sendMessage(user, conversation, new ChatMessageRequest("gastei 25 reais no supermercado hoje"), UUID.randomUUID());
        assertThat(supermarket.actionExecuted()).isTrue();
        assertThat(effects()).isEqualTo(4);
        assertThat(jdbc.queryForObject("select sum(amount) from transactions where user_id=? and type='EXPENSE'", BigDecimal.class, user)).isEqualByComparingTo("75.00");
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=? and status='SUCCESSFUL'", Integer.class, user)).isEqualTo(4);
    }

    @Test void monthlyQueryUsesOfficialPeriodWithoutWriting() {
        var category = jdbc.queryForObject("select id from categories where user_id=? and name='Transporte'", UUID.class, user);
        jdbc.update("insert into transactions(user_id,category_id,type,amount,transaction_date) values(?,?,'EXPENSE',25,CURRENT_DATE)", user, category);
        jdbc.update("insert into transactions(user_id,category_id,type,amount,transaction_date) values(?,?,'EXPENSE',75,date_trunc('month',CURRENT_DATE)::date-1)", user, category);
        var query = conversations.sendMessage(user, conversation, new ChatMessageRequest("quanto gastei este mês?"), UUID.randomUUID());
        assertThat(query.actionExecuted()).isFalse();
        assertThat(query.assistantMessage().content()).contains("Despesas: R$ 25,00").doesNotContain("recorte solicitado");
        assertThat(effects()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?", Integer.class, user)).isZero();
    }

    @Test void backendRejectionCannotBecomeAnAiSuccessConfirmation() {
        var message = UUID.randomUUID();
        var requestId = UUID.randomUUID().toString();
        var text = "gastei 25 reais no Uber hoje";
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER',?)", message, conversation, text);
        var proposed = ai.process(new AIServiceRequest(requestId, "1", conversation.toString(), text,
                ConversationContext.of(List.of()), null, null, turns.buildFinancialSnapshot(user), null));
        assertThat(proposed.decision().type()).isEqualTo(DecisionType.ACTION_REQUEST);
        // A category can become ineligible after interpretation; the backend must revalidate it.
        jdbc.update("update categories set type='INCOME' where user_id=? and name='Transporte'", user);
        var receipt = executions.execute(user, conversation, message, requestId, proposed.action_request(), null);
        assertThat(receipt.confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(effects()).isZero();
        assertThat(jdbc.queryForObject("select status from action_executions where user_id=?", String.class, user)).isEqualTo("REJECTED");
        var composed = ai.process(new AIServiceRequest(UUID.randomUUID().toString(), "1", conversation.toString(), text,
                ConversationContext.of(List.of()), null, receipt.action(), null, receipt.confirmation()));
        assertThat(composed.decision().reason()).isEqualTo("execution_confirmation_not_matching");
        assertThat(composed.action_request()).isNull();
        assertThat(composed.response().message()).contains("Nenhuma conclus\u00e3o foi registrada.").doesNotContain("Registrei");
    }

    @Test void foreignConversationCannotExecuteOrConfirm() {
        assertThatThrownBy(() -> conversations.sendMessage(UUID.randomUUID(), conversation,
                new ChatMessageRequest("gastei 25 reais no Uber hoje"), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(effects()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?", Integer.class, user)).isZero();
    }
}
