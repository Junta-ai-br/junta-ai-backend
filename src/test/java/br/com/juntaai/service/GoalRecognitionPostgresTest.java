package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.goal.GoalRequest;
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

/** Real local PostgreSQL, financial effects and receipt replay; no paid AI. */
@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
class GoalRecognitionPostgresTest extends AbstractIntegrationTest {
    @Autowired GoalCommandService commands;
    @Autowired GoalService goals;
    @Autowired ActionExecutionService actions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockBean AiClient ai;
    UUID user, goal;

    @BeforeEach void seed() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql://127.0.0.1:54837/junta_i3_disposable_");
        }
        user = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", user, "Synthetic", user + "@test.invalid");
        UUID category = UUID.randomUUID();
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Synthetic funding','INCOME')", category, user);
        jdbc.update("insert into transactions(id,user_id,category_id,type,amount,transaction_date) values(?,?,?,'INCOME',20000,current_date)", UUID.randomUUID(), user, category);
        goal = commands.create(user, UUID.randomUUID(), new GoalRequest("Viagem", new BigDecimal("100"), null)).id();
    }

    record Executed(ActionExecutionService.Receipt receipt, UUID conversation, UUID message, String pending) {}
    Executed contribution(String value) throws Exception {
        UUID conversation = UUID.randomUUID(), message = UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','sim')", message, conversation);
        var action = new ActionRequest(UUID.randomUUID().toString(), Operation.GOAL_CONTRIBUTION,
            new ParsedFinancialData(new BigDecimal(value), "synthetic", null, null, null, "Viagem",
                Operation.GOAL_CONTRIBUTION, TemporalStatus.REALIZED, null), "authoritative_state", Instant.now().plusSeconds(600));
        String pending = mapper.writeValueAsString(new PendingOperation(PendingState.GOAL, Operation.GOAL_CONTRIBUTION,
            action.payload(), java.util.List.of("confirmation"), goal.toString()));
        jdbc.update("update conversations set pending_state=? where id=?", pending, conversation);
        return new Executed(actions.execute(user, conversation, message, "synthetic", action, pending), conversation, message, pending);
    }
    long count() { return jdbc.queryForObject("select confirmed_contribution_count from goals where id=?", Long.class, goal); }

    @Test void firstSixContributionsHaveFrozenIndividualCountsAndExactMoney() throws Exception {
        for (int n = 1; n <= 6; n++) {
            var result = contribution("1.00");
            var evidence = result.receipt().confirmation().goal_recognition();
            assertThat(evidence.version()).isEqualTo("goal-recognition-v1");
            assertThat(evidence.goal_id()).isEqualTo(goal.toString());
            assertThat(evidence.contribution_number()).isEqualTo(n);
            assertThat(new BigDecimal(evidence.after_amount()).subtract(new BigDecimal(evidence.before_amount()))).isEqualByComparingTo("1.00");
            assertThat(evidence.new_milestone()).isNull();
        }
        assertThat(count()).isEqualTo(6);
    }

    @Test void largestCrossingCoversLowerMilestonesAcrossWithdrawalAndTargetEdit() throws Exception {
        var first = contribution("80").receipt().confirmation().goal_recognition();
        assertThat(first.new_milestone()).isEqualTo(75);
        assertThat(first.recognized_after()).isEqualTo(75);
        goals.withdrawProgress(user, goal, new BigDecimal("60"));
        assertThat(contribution("5").receipt().confirmation().goal_recognition().new_milestone()).isNull();
        goals.update(user, goal, new GoalRequest("Viagem", new BigDecimal("200"), null));
        assertThat(contribution("80").receipt().confirmation().goal_recognition().new_milestone()).isNull();
        var completed = contribution("100").receipt().confirmation().goal_recognition();
        assertThat(completed.new_milestone()).isEqualTo(100);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        goals.withdrawProgress(user, goal, new BigDecimal("150"));
        assertThat(contribution("150").receipt().confirmation().goal_recognition().new_milestone()).isNull();
    }

    @Test void httpContributionsAndChatShareCoverageAndBothKindsOfReplayAreIdempotent() throws Exception {
        UUID key = UUID.randomUUID();
        commands.progress(user, key, goal, new BigDecimal("10"));
        commands.progress(user, key, goal, new BigDecimal("10"));
        var result = contribution("15");
        assertThat(result.receipt().confirmation().goal_recognition().contribution_number()).isEqualTo(2);
        commands.progress(user, UUID.randomUUID(), goal, BigDecimal.ONE);
        var replay = actions.execute(user, result.conversation(), result.message(), "synthetic", result.receipt().action(), result.pending());
        assertThat(replay.confirmation()).isEqualTo(result.receipt().confirmation());
        assertThat(count()).isEqualTo(3);
    }

    @Test void concurrentCommandsSerializeCountAndRepeatedKeyDoesNotIncrementTwice() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var jobs = new java.util.ArrayList<Future<?>>();
            UUID repeated = UUID.randomUUID();
            for (int n = 0; n < 8; n++) {
                UUID key = n < 4 ? repeated : UUID.randomUUID();
                jobs.add(pool.submit(() -> commands.progress(user, key, goal, BigDecimal.ONE)));
            }
            for (var job : jobs) job.get(30, TimeUnit.SECONDS);
            assertThat(count()).isEqualTo(5);
            assertThat(jdbc.queryForObject("select current_amount from goals where id=?", BigDecimal.class, goal)).isEqualByComparingTo("5");
        } finally { pool.shutdownNow(); }
    }

    @Test void legacyGoalsKeepUnknownCoverageAndRejectedActionsDoNotCount() throws Exception {
        var rejected = contribution("20001").receipt().confirmation();
        assertThat(rejected.status()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(rejected.goal_recognition()).isNull();
        assertThat(count()).isZero();
        jdbc.update("update goals set confirmed_contribution_count=null,recognized_milestone=null where id=?", goal);
        assertThat(contribution("80").receipt().confirmation().goal_recognition()).isNull();
        assertThat(jdbc.queryForObject("select confirmed_contribution_count from goals where id=?", Long.class, goal)).isNull();
    }

    @Test void recognitionIsPerGoalAndConstraintRejectsPartialCoverage() throws Exception {
        var other = commands.create(user, UUID.randomUUID(), new GoalRequest("Reserva", new BigDecimal("100"), null)).id();
        contribution("5");
        commands.progress(user, UUID.randomUUID(), other, new BigDecimal("10"));
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select confirmed_contribution_count from goals where id=?", Long.class, other)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update goals set confirmed_contribution_count=null where id=?", goal))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void sharedWireFixtureAndLegacyReceiptsDeserializeCompatibly() throws Exception {
        try (var stream = getClass().getResourceAsStream("/goal-recognition-contract.json")) {
            var request = mapper.readValue(stream, AIServiceRequest.class);
            var evidence = request.execution_confirmation().goal_recognition();
            assertThat(evidence.action_id()).isEqualTo(request.pending_action().action_id());
            assertThat(evidence.contribution_number()).isEqualTo(3);
            assertThat(evidence.new_milestone()).isEqualTo(25);
            assertThat(mapper.readValue(mapper.writeValueAsString(request), AIServiceRequest.class)).isEqualTo(request);
        }
        var legacy = mapper.readValue("{\"action_id\":\"legacy\",\"status\":\"successful\",\"executed_operation\":\"goal_contribution\"}", ExecutionConfirmation.class);
        assertThat(legacy.goal_recognition()).isNull();
    }
}
