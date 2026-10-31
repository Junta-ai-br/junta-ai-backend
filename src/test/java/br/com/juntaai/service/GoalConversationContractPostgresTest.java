package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.goal.GoalRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: real deterministic AI HTTP service on loopback and disposable PostgreSQL. */
@EnabledIfEnvironmentVariable(named="GOALS_REAL_AI", matches="true")
@TestPropertySource(properties="ai.service.base-url=http://127.0.0.1:58123")
class GoalConversationContractPostgresTest extends AbstractIntegrationTest {
    @Test void realHttpWithdrawalPreservesTheNumericSnapshotCents() {
        jdbc.update("update goals set target_amount=999999999999999.99,current_amount=100000000000000.07 where id=?",goal);
        UUID conversation=conversations.createConversation(user,"Synthetic").id();
        var first=conversations.sendMessage(user,conversation,
                new ChatMessageRequest("Retirei R$ 100000000000000,07 da meta viagem"),UUID.randomUUID());
        assertThat(first.actionExecuted()).isFalse();
        assertThat(first.assistantMessage().content()).contains("liberar reserva");
        confirmWithdrawal(conversation);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
    }

    @Test void realHttpWithdrawalConfirmsReopensAndReplays() {
        jdbc.update("update goals set target_amount=100,current_amount=100,status='COMPLETED' where id=?",goal);
        UUID conversation=conversations.createConversation(user,"Synthetic").id(),key=UUID.randomUUID();
        var input=new ChatMessageRequest("Tirei 50 reais da minha meta de viagem.");
        assertThat(conversations.sendMessage(user,conversation,input,key).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("liberar reserva"),UUID.randomUUID()).actionExecuted()).isFalse();
        UUID confirmationKey = UUID.randomUUID();
        var reply=conversations.sendMessage(user,conversation,new ChatMessageRequest("sim"),confirmationKey);
        assertThat(reply.actionExecuted()).isTrue();
        assertThat(reply.assistantMessage().content()).contains("retirada");
        conversations.sendMessage(user,conversation,new ChatMessageRequest("sim"),confirmationKey);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("50.00");
        assertThat(jdbc.queryForObject("select status from goals where id=?",String.class,goal)).isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
    }
    @Test void realHttpWithdrawalClarifiesMissingAmountAndGoal() {
        jdbc.update("update goals set current_amount=100 where id=?",goal);
        UUID conversation=conversations.createConversation(user,"Synthetic").id();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("Retirei da minha meta de viagem."),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("25 reais"),UUID.randomUUID()).actionExecuted()).isFalse();
        confirmWithdrawal(conversation);
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("Tirei 10 reais."),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("viagem"),UUID.randomUUID()).actionExecuted()).isFalse();
        confirmWithdrawal(conversation);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("65.00");
    }
    @Autowired ConversationService conversations;
    @Autowired GoalCommandService commands;
    @Autowired JdbcTemplate jdbc;
    UUID user, goal;
    @BeforeEach void seed() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) { assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_"); }
        user=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        goal=commands.create(user,UUID.randomUUID(),new GoalRequest("viagem",new BigDecimal("1000.00"),null)).id();
    }
    @Test void realHttpContributionCommitsConfirmsAndReplays() {
        UUID conversation=conversations.createConversation(user,"Synthetic").id();
        UUID key=UUID.randomUUID();
        var input=new ChatMessageRequest("Separei 150 reais para minha viagem.");
        var proposal=conversations.sendMessage(user,conversation,input,key);
        assertThat(proposal.actionExecuted()).isFalse();
        assertThat(proposal.assistantMessage().quickReplies()).hasSize(2);
        var selection = proposal.assistantMessage().quickReplies().getFirst();
        UUID sourceKey = UUID.randomUUID();
        var sourceRequest = new ChatMessageRequest("Texto visual não é autorização", selection);
        proposal = conversations.sendMessage(user,conversation,sourceRequest,sourceKey);
        assertThat(proposal.actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,sourceRequest,sourceKey)).isEqualTo(proposal);
        assertThatThrownBy(() -> conversations.sendMessage(user,conversation,sourceRequest,UUID.randomUUID()))
                .isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
        assertThat(proposal.assistantMessage().content()).contains("Confirma");
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("0");
        assertThat(conversations.listMessages(user,conversation).stream().flatMap(m -> m.quickReplies().stream())).isEmpty();
        UUID confirmationKey=UUID.randomUUID();
        var confirmation=new ChatMessageRequest("sim");
        var reply=conversations.sendMessage(user,conversation,confirmation,confirmationKey);
        assertThat(reply.actionExecuted()).isTrue();
        conversations.sendMessage(user,conversation,confirmation,confirmationKey);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
    }
    @Test void realHttpClarificationThenContribution() {
        UUID conversation=conversations.createConversation(user,"Synthetic").id();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("Guardei para minha viagem."),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("50 reais"),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("já registrei"),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("50.00");
    }
    @Test void realHttpHomonymsCannotExecute() {
        commands.create(user,UUID.randomUUID(),new GoalRequest("viagem",new BigDecimal("2000.00"),null));
        UUID conversation=conversations.createConversation(user,"Synthetic").id();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("Reservei 50 reais para minha viagem."),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(jdbc.queryForObject("select sum(current_amount) from goals where user_id=?",BigDecimal.class,user)).isEqualByComparingTo("0");
    }
    @Test void realHttpMissingGoalUsesExactPendingName() {
        UUID conversation=conversations.createConversation(user,"Synthetic").id();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("Guardei 25 reais."),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("viagem"),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("já registrei"),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("25.00");
    }

    void confirmWithdrawal(UUID conversation) {
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("liberar reserva"),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID()).actionExecuted()).isTrue();
    }

    @Test void cancelledAndReplacedPromptsRejectStaleSourceChoices() {
        UUID conversation = conversations.createConversation(user,"Synthetic").id();
        var first = conversations.sendMessage(user,conversation,new ChatMessageRequest("Guardei 20 reais para viagem"),UUID.randomUUID());
        var old = first.assistantMessage().quickReplies().getFirst();
        assertThat(conversations.listMessages(user,conversation).getLast().quickReplies()).hasSize(2);
        var repeatedPrompt = conversations.sendMessage(user,conversation,new ChatMessageRequest("oi"),UUID.randomUUID());
        assertThat(repeatedPrompt.assistantMessage().quickReplies().getFirst().pending_id()).isNotEqualTo(old.pending_id());
        assertThatThrownBy(() -> conversations.sendMessage(user,conversation,new ChatMessageRequest("sim",old),UUID.randomUUID()))
            .isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
        conversations.sendMessage(user,conversation,new ChatMessageRequest("cancelar"),UUID.randomUUID());
        assertThatThrownBy(() -> conversations.sendMessage(user,conversation,new ChatMessageRequest("sim",old),UUID.randomUUID()))
            .isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
        var second = conversations.sendMessage(user,conversation,new ChatMessageRequest("Guardei 30 reais para viagem"),UUID.randomUUID());
        assertThat(second.assistantMessage().quickReplies().getFirst().pending_id()).isNotEqualTo(old.pending_id());
        assertThatThrownBy(() -> conversations.sendMessage(user,conversation,new ChatMessageRequest("sim",old),UUID.randomUUID()))
            .isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("0");
    }

    @Test void previousResourcesCanBeReservedWithoutInventingIncomeOrAvailability() {
        UUID conversation = conversations.createConversation(user,"Synthetic").id();
        var first = conversations.sendMessage(user,conversation,new ChatMessageRequest("Guardei 20 reais para viagem"),UUID.randomUUID());
        var selection = first.assistantMessage().quickReplies().get(1);
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("novo",selection),UUID.randomUUID()).actionExecuted()).isFalse();
        var proposal = conversations.sendMessage(user,conversation,new ChatMessageRequest("dinheiro anterior ao Junta.ai"),UUID.randomUUID());
        assertThat(proposal.actionExecuted()).isFalse();
        assertThat(proposal.assistantMessage().content()).contains("saldo inicial", "conciliado");
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
    }
}
