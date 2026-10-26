package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.exception.*;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** No production data or paid provider; exercises real PostgreSQL locks and commits. */
@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
class GoalWritePostgresTest extends AbstractIntegrationTest {
    @Autowired GoalCommandService commands;
    @Autowired GoalService goals;
    @Autowired ActionExecutionService actions;
    @Autowired ConversationTurnStore turns;
    @Autowired JdbcTemplate jdbc;
    @MockBean AiClient ai;
    UUID user, goal;

    @BeforeEach void seed() throws Exception {
        try (var c = jdbc.getDataSource().getConnection()) {
            assertThat(c.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", user, "Synthetic", user + "@test.invalid");
        goal = commands.create(user, UUID.randomUUID(), new GoalRequest("Viagem", new BigDecimal("1000.00"), null)).id();
    }
    BigDecimal accumulated() { return jdbc.queryForObject("select current_amount from goals where id=?", BigDecimal.class, goal); }

    @Test void retriesRecoverCreateProgressAndDeleteReceipts() {
        UUID key = UUID.randomUUID();
        var body = new GoalRequest("Reserva", new BigDecimal("200.01"), null);
        var first = commands.create(user, key, body);
        assertThat(commands.create(user, key, body)).isEqualTo(first);
        assertThatThrownBy(() -> commands.create(user, key, new GoalRequest("Outra", body.targetAmount(), null))).isInstanceOf(ConflictException.class);
        UUID progress = UUID.randomUUID();
        var receipt = commands.progress(user, progress, goal, new BigDecimal("25.01"));
        assertThat(commands.progress(user, progress, goal, new BigDecimal("25.01"))).isEqualTo(receipt);
        assertThat(accumulated()).isEqualByComparingTo("25.01");
        UUID deletion = UUID.randomUUID(); commands.delete(user, deletion, goal); commands.delete(user, deletion, goal);
        assertThat(jdbc.queryForObject("select count(*) from goals where id=?", Integer.class, goal)).isZero();
    }

    @Test void concurrentDifferentHttpCommandsDoNotLoseUpdates() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        try {
            var start = new CountDownLatch(1);
            var jobs = new java.util.ArrayList<Future<?>>();
            for (int i=0; i<16; i++) jobs.add(pool.submit(() -> { start.await(); commands.progress(user, UUID.randomUUID(), goal, new BigDecimal("0.01")); return null; }));
            start.countDown(); for (var job : jobs) job.get(20, TimeUnit.SECONDS);
            assertThat(accumulated()).isEqualByComparingTo("0.16");
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentSameHttpCommandHasOneEffect() throws Exception {
        var pool = Executors.newFixedThreadPool(2); UUID key = UUID.randomUUID();
        try {
            var a = pool.submit(() -> commands.progress(user, key, goal, new BigDecimal("12.34")));
            var b = pool.submit(() -> commands.progress(user, key, goal, new BigDecimal("12.34")));
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
            assertThat(accumulated()).isEqualByComparingTo("12.34");
        } finally { pool.shutdownNow(); }
    }

    @Test void chatAndHttpSerializeAndChatReplayHasNoSecondEffect() throws Exception {
        UUID conversation=UUID.randomUUID(), message=UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation,user);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','synthetic')",message,conversation);
        var action = new ActionRequest(UUID.randomUUID().toString(), Operation.GOAL_CONTRIBUTION,
                new ParsedFinancialData(new BigDecimal("25.01"), "synthetic", null, null, null, "Viagem", Operation.GOAL_CONTRIBUTION, TemporalStatus.REALIZED, null), "authoritative_state", Instant.now().plusSeconds(600));
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(() -> actions.execute(user,conversation,message,"synthetic",action,null));
            var b=pool.submit(() -> commands.progress(user,UUID.randomUUID(),goal,new BigDecimal("10.02")));
            var receipt=a.get(20,TimeUnit.SECONDS); b.get(20,TimeUnit.SECONDS);
            assertThat(receipt.confirmation().status()).isEqualTo(ExecutionStatus.SUCCESSFUL);
            assertThat(actions.execute(user,conversation,message,"synthetic",action,null).confirmation()).isEqualTo(receipt.confirmation());
            assertThat(accumulated()).isEqualByComparingTo("35.03");
            assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void ownerPrecisionCompletionAndDeletedGoalsAreValidated() {
        UUID other=UUID.randomUUID();
        assertThatThrownBy(() -> goals.addProgress(other,goal,BigDecimal.ONE)).isInstanceOf(ResourceNotFoundException.class);
        for (String amount : new String[]{"-1", "0", "1.001", "1000000000000000"}) {
            assertThatThrownBy(() -> goals.addProgress(user,goal,new BigDecimal(amount))).isInstanceOf(BusinessRuleException.class);
        }
        assertThat(goals.addProgress(user,goal,new BigDecimal("1000.00")).status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> goals.addProgress(user,goal,BigDecimal.ONE)).isInstanceOf(BusinessRuleException.class);
        goals.delete(user,goal);
        assertThatThrownBy(() -> goals.addProgress(user,goal,BigDecimal.ONE)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void snapshotPreservesHomonymsWithoutOverwriting() {
        commands.create(user,UUID.randomUUID(),new GoalRequest("Viagem",new BigDecimal("200.00"),null));
        assertThat(turns.buildFinancialSnapshot(user).goal_progress()).hasSize(2);
    }
}
