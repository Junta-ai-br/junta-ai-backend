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
        var reply=conversations.sendMessage(user,conversation,input,key);
        assertThat(reply.actionExecuted()).isTrue();
        conversations.sendMessage(user,conversation,input,key);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
    }
    @Test void realHttpClarificationThenContribution() {
        UUID conversation=conversations.createConversation(user,"Synthetic").id();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("Guardei para minha viagem."),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("50 reais"),UUID.randomUUID()).actionExecuted()).isTrue();
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
        assertThat(conversations.sendMessage(user,conversation,new ChatMessageRequest("viagem"),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,goal)).isEqualByComparingTo("25.00");
    }
}
