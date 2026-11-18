package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.planner.SimulationRequest;
import br.com.juntaai.entity.TransactionType;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real PostgreSQL, two backend JVMs/pools; only the AI provider is synthetic. */
@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinancialWriteCoordinationPostgresTest extends AbstractIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationService chat;
    @Autowired ConversationTurnStore turns;
    @Autowired MessageEntryStore entries;
    @Autowired ActionExecutionService executor;
    @Autowired PlannerSimulationService simulations;
    @Autowired TransactionService transactions;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean AiClient ai;
    Process worker;
    BufferedReader output;
    PrintWriter input;
    BufferedWriter processLog;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    UUID user, conversation, category, transaction, goal, simulation, message, key;
    ActionRequest action;
    String pending;
    QuickReplySelection selection;

    @BeforeAll void startWorker() throws Exception {
        try (var c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL()).startsWith("jdbc:postgresql://127.0.0.1:")
                    .contains("/junta_i3_disposable_");
        }
        Path temp = Files.createTempDirectory("junta-lock-process-");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path args = temp.resolve("java.args");
        Files.writeString(args, "-cp\n\"" + classpath.replace('\\', '/') + "\"\n"
                + FinancialWriteProcess.class.getName() + "\n", StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        worker = new ProcessBuilder(java, "@" + args).redirectErrorStream(true).start();
        output = new BufferedReader(new InputStreamReader(worker.getInputStream(), StandardCharsets.UTF_8));
        input = new PrintWriter(new OutputStreamWriter(worker.getOutputStream(), StandardCharsets.UTF_8), true);
        processLog = Files.newBufferedWriter(temp.resolve("backend.log"), StandardCharsets.UTF_8);
        assertThat(io.submit(this::readFrame).get(60, TimeUnit.SECONDS)).isEqualTo("READY");
    }

    @AfterAll void stopWorker() throws Exception {
        if (input != null) input.close();
        if (worker != null && !worker.waitFor(15, TimeUnit.SECONDS)) worker.destroyForcibly();
        io.shutdownNow();
        if (output != null) output.close();
        if (processLog != null) processLog.close();
    }

    String readFrame() throws IOException {
        for (String line; (line = output.readLine()) != null;) {
            processLog.write(line); processLog.newLine(); processLog.flush();
            if (line.startsWith(FinancialWriteProcess.FRAME)) return line.substring(FinancialWriteProcess.FRAME.length());
        }
        throw new EOFException("Second backend JVM exited; see temporary backend.log");
    }

    Future<String> send(FinancialWriteProcess.Input request) {
        return io.submit(() -> { input.println(objectMapper.writeValueAsString(request)); return readFrame(); });
    }

    FinancialWriteProcess.Input request(String operation) {
        return new FinancialWriteProcess.Input(operation, user, conversation, category, transaction,
                goal, simulation, message, key, action, pending, selection);
    }

    @BeforeEach void seed() {
        user = UUID.randomUUID(); conversation = UUID.randomUUID(); category = UUID.randomUUID();
        transaction = UUID.randomUUID(); goal = UUID.randomUUID(); message = UUID.randomUUID(); key = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?, 'Synthetic locks', ?)", user, user + "@test.invalid");
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Synthetic coordination','BOTH')", category, user);
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'Synthetic goal',100,0)", goal, user);
        jdbc.update("insert into transactions(id,user_id,category_id,type,amount,transaction_date) values(?,?,?,'INCOME',1000,'2020-01-01')",
                UUID.randomUUID(), user, category);
        jdbc.update("insert into transactions(id,user_id,category_id,type,amount,transaction_date) values(?,?,?,'EXPENSE',2,'2020-01-01')",
                transaction, user, category);
        simulation = simulations.create(user, new SimulationRequest("Synthetic import", new BigDecimal("100"),
                10, "no_history", new BigDecimal("10"), 10, null)).id();
        action = new ActionRequest(UUID.randomUUID().toString(), Operation.EXPENSE,
                new ParsedFinancialData(new BigDecimal("45.00"), "Synthetic expense", "Synthetic coordination", null,
                        "2020-01-01", null, Operation.EXPENSE, TemporalStatus.REALIZED, null),
                "authoritative_state", Instant.now().plusSeconds(600));
        pending = null; selection = null;
        when(ai.process(any())).thenAnswer(c -> FinancialWriteProcess.reply(c.getArgument(0)));
    }

    void prepare(String operation) {
        if (Set.of("SELECT", "FAIL", "COMPLETE", "COMPLETED").contains(operation)) {
            var admission = entries.admit(user, conversation, key, "synthetic");
            message = admission.turn().messageId();
            if (operation.equals("COMPLETED")) entries.completeResponse(user, conversation, admission.turn(), "synthetic", null, false);
        } else if (operation.equals("FINISH")) {
            message = turns.begin(user, conversation, "synthetic").messageId();
        } else if (Set.of("EXECUTE", "RECEIPT").contains(operation)) {
            jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','confirmo')", message, conversation);
            pending = TransactionConsentFixture.select(jdbc, objectMapper, user, conversation, message,
                    "lock-test-request", action).json();
            if (operation.equals("RECEIPT")) executor.execute(user, conversation, message, "lock-test-request", action, pending);
        } else if (operation.equals("CAT_DELETE")) {
            category = UUID.randomUUID();
            jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Unused category','BOTH')", category, user);
        }
    }

    void awaitBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from pg_stat_activity
                    where application_name='financial-lock-worker' and ?=any(pg_blocking_pids(pid)))
                    """, Boolean.class, blocker))) return;
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        } while (System.nanoTime() < deadline);
        fail("Second backend did not wait on the owner's database lock");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIT", "BEGIN", "FINISH", "SELECT", "FAIL", "COMPLETE", "COMPLETED",
            "EXECUTE", "RECEIPT", "IMPORT", "TX_CREATE", "TX_UPDATE", "TX_DELETE", "TX_CORRECT",
            "CAT_CREATE", "CAT_UPDATE", "CAT_DELETE", "GOAL_PROGRESS", "GOAL_UPDATE", "GOAL_DELETE", "ACCOUNT_DELETE"})
    void ownerLockPrecedesConversationAndResourcesAcrossProcesses(String operation) throws Exception {
        prepare(operation);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.createStatement().execute("set local lock_timeout='700ms'");
            try (var lock = c.prepareStatement("select id from users where id=? for update")) {
                lock.setObject(1, user); lock.executeQuery().close();
            }
            int blocker;
            try (var rows = c.createStatement().executeQuery("select pg_backend_pid()")) { rows.next(); blocker = rows.getInt(1); }
            Future<String> result = send(request(operation));
            try {
                awaitBlockedBy(blocker);
                // On the old chat order, the other process holds this row while waiting on our user:
                // this SELECT times out. The new order leaves all resource rows available.
                for (var item : Map.of("conversations", conversation, "transactions", transaction,
                        "categories", category, "goals", goal, "planner_simulations", simulation).entrySet()) {
                    try (var lock = c.prepareStatement("select id from " + item.getKey() + " where id=? for update")) {
                        lock.setObject(1, item.getValue()); lock.executeQuery().close();
                    }
                }
            } finally {
                c.rollback();
                assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("OK");
            }
        }
    }

    @Test void unrelatedOwnerContinuesWhileAnotherOwnerIsLocked() throws Exception {
        UUID blocked = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,'Synthetic other',?)", blocked, blocked + "@test.invalid");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var lock = c.prepareStatement("select id from users where id=? for update")) {
                lock.setObject(1, blocked); lock.executeQuery().close();
            }
            try { assertThat(send(request("ADMIT")).get(5, TimeUnit.SECONDS)).isEqualTo("OK"); }
            finally { c.rollback(); }
        }
    }

    void publish() {
        var preview = TransactionConsentFixture.publish(jdbc, objectMapper, conversation, action);
        pending = preview.json(); selection = preview.selection();
    }

    @Test void transactionCrudAndConfirmedChatSerializeAcrossProcesses() throws Exception {
        publish();
        int before = jdbc.queryForObject("select count(*) from transactions where user_id=?", Integer.class, user);
        Future<String> remote = new TransactionTemplate(transactionManager).execute(status -> {
            int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
            jdbc.queryForObject("select id from users where id=? for update", UUID.class, user);
            Future<String> result = send(request("CONFIRM"));
            try { awaitBlockedBy(blocker); } catch (Exception e) { throw new AssertionError(e); }
            transactions.create(user, FinancialWriteProcess.transaction(request("TX_CREATE")));
            return result;
        });
        assertThat(remote.get(15, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?", Integer.class, user)).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=? and status='SUCCESSFUL'", Integer.class, user)).isEqualTo(1);
    }

    @Test void concurrentEquivalentConfirmationsReplayAndDivergentBodyConflicts() throws Exception {
        publish();
        String localOutcome, outcome;
        try (ExecutorService local = Executors.newSingleThreadExecutor(); Connection fence = dataSource.getConnection()) {
            fence.setAutoCommit(false);
            try (var lock = fence.prepareStatement("select id from users where id=? for update")) {
                lock.setObject(1, user); lock.executeQuery().close();
            }
            int blocker;
            try (var rows = fence.createStatement().executeQuery("select pg_backend_pid()")) { rows.next(); blocker = rows.getInt(1); }
            Future<String> remote = send(request("CONFIRM"));
            Future<String> sameJvm = local.submit(() -> {
                try {
                    chat.sendMessage(user, conversation, new ChatMessageRequest("Confirmar", selection), key);
                    return "OK";
                } catch (br.com.juntaai.exception.MessageEntryException ex) {
                    return "ERROR:" + ex.getCode();
                }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                int waiters;
                do {
                    waiters = jdbc.queryForObject("""
                            with recursive waiting(pid) as (
                                select pid from pg_stat_activity where datname=current_database()
                                and ?=any(pg_blocking_pids(pid))
                                union
                                select activity.pid from pg_stat_activity activity join waiting prior
                                on prior.pid=any(pg_blocking_pids(activity.pid))
                                where activity.datname=current_database()
                            ) select count(*) from waiting
                            """, Integer.class, blocker);
                    if (waiters >= 2) break;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                } while (System.nanoTime() < deadline);
                // PostgreSQL may queue the second waiter behind the first (a soft blocker),
                // so follow the blocking chain instead of requiring two direct blocker edges.
                assertThat(waiters).as("Both backend JVMs must contend before release").isGreaterThanOrEqualTo(2);
            } finally { fence.rollback(); }
            localOutcome = sameJvm.get(15, TimeUnit.SECONDS);
            outcome = remote.get(15, TimeUnit.SECONDS);
        }
        assertThat(localOutcome).isIn("OK", "ERROR:message_processing_in_progress");
        assertThat(outcome).isIn("OK", "ERROR:message_processing_in_progress");
        assertThat(localOutcome.equals("OK") || outcome.equals("OK")).isTrue();
        // An in-flight duplicate keeps the existing explicit retry contract, not a new financial action.
        chat.sendMessage(user, conversation, new ChatMessageRequest("Confirmar", selection), key);
        assertThat(send(request("CONFIRM")).get(15, TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=? and amount=45", Integer.class, user)).isEqualTo(1);
        assertThatThrownBy(() -> chat.sendMessage(user, conversation, new ChatMessageRequest("Different body"), key))
                .isInstanceOfSatisfying(br.com.juntaai.exception.MessageEntryException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("idempotency_key_conflict"));
    }

    @Test void foreignConversationDoesNotLockOrMutateItsOwner() throws Exception {
        UUID foreign = user;
        user = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,'Synthetic unauthorized',?)", user, user + "@test.invalid");
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var lock = c.prepareStatement("select id from users where id=? for update")) {
                lock.setObject(1, foreign); lock.executeQuery().close();
            }
            try { assertThat(send(request("ADMIT")).get(5, TimeUnit.SECONDS)).isEqualTo("ERROR:ResourceNotFoundException"); }
            finally { c.rollback(); }
        }
        assertThat(jdbc.queryForObject("select count(*) from messages where conversation_id=?", Integer.class, conversation)).isZero();
    }

    @Test void aiCallsRetainNoUserOrConversationLocks() throws Exception {
        publish();
        doAnswer(call -> {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                for (var item : Map.of("users", user, "conversations", conversation).entrySet()) {
                    try (var lock = c.prepareStatement("select id from " + item.getKey() + " where id=? for update nowait")) {
                        lock.setObject(1, item.getValue()); lock.executeQuery().close();
                    }
                }
                c.rollback();
            }
            return FinancialWriteProcess.reply(call.getArgument(0));
        }).when(ai).process(any());
        assertThat(chat.sendMessage(user, conversation, new ChatMessageRequest("Confirmar", selection), key).actionExecuted()).isTrue();
        verify(ai, times(2)).process(any());
    }
}
