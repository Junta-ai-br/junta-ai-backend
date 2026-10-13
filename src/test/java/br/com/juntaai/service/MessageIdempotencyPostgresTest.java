package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.JuntaAiApplication;
import br.com.juntaai.dto.conversation.*;
import br.com.juntaai.entity.Role;
import br.com.juntaai.exception.*;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL 16 and real Spring transactions. Only AI transport and controlled failures are mocked. */
@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class MessageIdempotencyPostgresTest extends AbstractIntegrationTest {
    @Autowired ConversationService service;
    @Autowired MessageEntryStore entries;
    @Autowired ActionExecutionService executions;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @SpyBean ConversationTurnStore turns;
    @MockBean AiClient ai;
    UUID user, conversation;
    AtomicInteger interpretations;

    @BeforeEach void seed() {
        assertThat(jdbc.queryForObject("show server_version_num", Integer.class)).isBetween(160000, 169999);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).startsWith("junta_i3_disposable_");
        user = UUID.randomUUID(); conversation = UUID.randomUUID(); interpretations = new AtomicInteger();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", user, "Synthetic", user + "@test.invalid");
        jdbc.update("insert into conversations(id,user_id) values(?,?)", conversation, user);
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'TestCategory','EXPENSE')", UUID.randomUUID(), user);
        doAnswer(inv -> {
            AIServiceRequest req = inv.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (req.execution_confirmation() != null) return response(req, null);
            interpretations.incrementAndGet();
            return response(req, action());
        }).when(ai).process(any());
    }

    private ActionRequest action() {
        return new ActionRequest(UUID.randomUUID().toString(), Operation.EXPENSE,
                new ParsedFinancialData(new BigDecimal("20.00"), "synthetic", "TestCategory", "hoje",
                        LocalDate.now().toString(), null, Operation.EXPENSE, TemporalStatus.REALIZED, null),
                "authoritative_state", Instant.now().plusSeconds(600));
    }
    private static ProcessingResult response(AIServiceRequest req, ActionRequest action) {
        return new ProcessingResult(req.request_id(), req.contract_version(), null,
                new Decision(action == null ? DecisionType.RESPONSE : DecisionType.ACTION_REQUEST,
                        action == null ? "execution_confirmed" : null, List.of()), action, null,
                new ConversationalResponse("synthetic reply", List.of()), null, null);
    }
    private ChatReplyResponse send(UUID key) { return service.sendMessage(user, conversation, new ChatMessageRequest("synthetic"), key); }
    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id=?", Integer.class, user);
    }
    private String state(UUID key) {
        return jdbc.queryForObject("select state from conversation_message_entries where user_id=? and idempotency_key=?", String.class, user, key);
    }
    private void expire(UUID key) {
        jdbc.update("update conversation_message_entries set processing_deadline=clock_timestamp()-interval '1 second' where user_id=? and idempotency_key=?", user, key);
    }
    private void failed(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOf(MessageEntryException.class)
                .extracting("code").isEqualTo("message_processing_failed");
    }
    private String token() { return jwt.generateAccessToken(user, user + "@test.invalid", Role.USER); }

    @Test void concurrentHttpRetriesAdmitAndInterpretOnce() throws Exception {
        UUID key = UUID.randomUUID();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(inv -> {
            AIServiceRequest req = inv.getArgument(0);
            if (req.execution_confirmation() != null) return response(req, null);
            interpretations.incrementAndGet(); entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return response(req, action());
        }).when(ai).process(any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> http(key));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                mockMvc.perform(post("/conversations/" + conversation + "/messages")
                                .header("Authorization", "Bearer " + token()).header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"synthetic\"}"))
                        .andExpect(status().isConflict()).andExpect(header().string("Retry-After", "2"))
                        .andExpect(jsonPath("$.code").value("message_processing_in_progress"));
                release.countDown();
                var accepted = first.get(20, TimeUnit.SECONDS);
                assertThat(http(key)).isEqualTo(accepted);
            } finally { release.countDown(); }
        }
        assertThat(interpretations.get()).isEqualTo(1);
        assertThat(count("conversation_message_entries")).isEqualTo(1);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from messages where conversation_id=? and role='USER'", Integer.class, conversation)).isEqualTo(1);
    }

    private ChatReplyResponse http(UUID key) throws Exception {
        String body = mockMvc.perform(post("/conversations/" + conversation + "/messages")
                        .header("Authorization", "Bearer " + token()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"synthetic\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, ChatReplyResponse.class);
    }

    @Test void lostReplyReplaysSameIdsWithoutCallingAi() {
        UUID key = UUID.randomUUID(); var first = send(key);
        clearInvocations(ai);
        assertThat(send(key)).isEqualTo(first);
        verifyNoInteractions(ai);
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test void completedReplayStillValidatesPersistedReceipt() {
        UUID key = UUID.randomUUID(); send(key);
        jdbc.update("update action_executions set receipt_json='{}' where user_id=?", user);
        clearInvocations(ai);
        assertThatThrownBy(() -> send(key)).isInstanceOf(BusinessRuleException.class).hasMessage("execution_evidence_invalid");
        verifyNoInteractions(ai); assertThat(count("transactions")).isEqualTo(1);
    }

    @Test void selectedEntryRejectsDifferentActionAndCannotBecomeInitialFailure() {
        UUID key = UUID.randomUUID(); var admitted = entries.admit(user, conversation, key, "synthetic");
        assertThat(entries.select(user, conversation, admitted.entry().messageId(), action())).isTrue();
        assertThatThrownBy(() -> executions.execute(user, conversation, admitted.entry().messageId(), admitted.entry().requestId(), action(), null))
                .isInstanceOf(BusinessRuleException.class).hasMessage("action_entry_mismatch");
        assertThat(entries.failInitial(user, conversation, admitted.entry().messageId())).isFalse();
        expire(key);
        assertThat(send(key).actionExecuted()).isTrue(); assertThat(count("transactions")).isEqualTo(1);
        assertThat(interpretations.get()).isZero();
    }

    @Test void divergentBodyAndAnotherOwnedConversationConflict() throws Exception {
        UUID key = UUID.randomUUID(); send(key);
        mockMvc.perform(post("/conversations/" + conversation + "/messages")
                        .header("Authorization", "Bearer " + token()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"changed\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency_key_conflict"));
        UUID another = UUID.randomUUID();
        jdbc.update("insert into conversations(id,user_id) values(?,?)", another, user);
        assertThatThrownBy(() -> service.sendMessage(user, another, new ChatMessageRequest("synthetic"), key))
                .isInstanceOf(MessageEntryException.class).extracting("code").isEqualTo("idempotency_key_conflict");
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("conversation_message_entries")).isEqualTo(1);
    }

    @Test void identicalTextWithNewKeysRemainsTwoIntentions() {
        var first = send(UUID.randomUUID()); var second = send(UUID.randomUUID());
        assertThat(first.userMessage().id()).isNotEqualTo(second.userMessage().id());
        assertThat(count("transactions")).isEqualTo(2);
        assertThat(count("conversation_message_entries")).isEqualTo(2);
    }

    @Test void initialFailureIsTerminalAndDoesNotReinterpret() {
        UUID key = UUID.randomUUID();
        doThrow(new AiServiceUnavailableException("controlled_timeout")).when(ai).process(any());
        failed(() -> send(key)); failed(() -> send(key));
        verify(ai, times(1)).process(any());
        assertThat(state(key)).isEqualTo("FAILED"); assertThat(count("transactions")).isZero();
    }

    @Test void orphanedInitialEntryExpiresWithoutAnotherAiCall() {
        UUID key = UUID.randomUUID(); entries.admit(user, conversation, key, "synthetic");
        expire(key); clearInvocations(ai);
        failed(() -> send(key)); verifyNoInteractions(ai);
        assertThat(state(key)).isEqualTo("FAILED"); assertThat(count("action_executions")).isZero();
    }

    @Test void lateCallbackCannotSelectAfterDeadlineOrRetryFailure() throws Exception {
        UUID key = UUID.randomUUID(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown(); assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return response(inv.getArgument(0), action());
        }).when(ai).process(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> send(key));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); expire(key);
                failed(() -> send(key)); release.countDown();
                assertThatThrownBy(() -> first.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(MessageEntryException.class);
            } finally { release.countDown(); }
        }
        assertThat(state(key)).isEqualTo("FAILED"); assertThat(count("action_executions")).isZero();
        verify(ai, times(1)).process(any());
    }

    @Test void lateCallbackAloneCannotSelectAfterDeadline() {
        UUID key = UUID.randomUUID();
        doAnswer(inv -> { expire(key); return response(inv.getArgument(0), action()); }).when(ai).process(any());
        failed(() -> send(key));
        assertThat(state(key)).isEqualTo("FAILED"); assertThat(count("transactions")).isZero();
    }

    @Test void financialRollbackKeepsExactSelectionForRetry() {
        UUID key = UUID.randomUUID();
        // Admission builds one snapshot; only the post-write snapshot fails and rolls back effect/receipt.
        doAnswer(inv -> {
            Object snapshot = inv.callRealMethod(); // Queries flush the financial write before the controlled failure.
            if (count("transactions") > 0) throw new IllegalStateException("controlled_snapshot_failure");
            return snapshot;
        }).when(turns).buildFinancialSnapshot(user);
        assertThatThrownBy(() -> send(key)).isInstanceOf(IllegalStateException.class);
        assertThat(state(key)).isEqualTo("ACTION_SELECTED");
        assertThat(count("transactions")).isZero(); assertThat(count("action_executions")).isZero();
        String selected = jdbc.queryForObject("select selected_action_id from conversation_message_entries where user_id=?", String.class, user);
        doCallRealMethod().when(turns).buildFinancialSnapshot(user);
        expire(key); // Initial deadline must never fail a selected entry.
        assertThat(send(key).actionExecuted()).isTrue();
        assertThat(count("transactions")).isEqualTo(1); assertThat(interpretations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select action_id from action_executions where user_id=?", String.class, user)).isEqualTo(selected);
    }

    @Test void concurrentResumptionOfPersistedSelectionHasOneEffect() throws Exception {
        UUID key = UUID.randomUUID(); var admitted = entries.admit(user, conversation, key, "synthetic");
        assertThat(entries.select(user, conversation, admitted.entry().messageId(), action())).isTrue();
        var start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return send(key); });
            var second = pool.submit(() -> { start.await(); return send(key); });
            assertThat(first.get(20, TimeUnit.SECONDS).assistantMessage().id())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).assistantMessage().id());
        }
        assertThat(interpretations.get()).isZero(); assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("action_executions")).isEqualTo(1);
    }

    @Test void lostOutcomeAfterSelectionNeverMarksInitialFailure() {
        UUID key = UUID.randomUUID();
        doAnswer(inv -> {
            AIServiceRequest req = inv.getArgument(0);
            if (req.execution_confirmation() != null) return response(req, null);
            var admitted = entries.admit(user, conversation, key, "synthetic");
            assertThat(entries.select(user, conversation, admitted.entry().messageId(), action())).isTrue();
            throw new AiServiceUnavailableException("controlled_lost_selection_outcome");
        }).when(ai).process(any());
        assertThat(send(key).actionExecuted()).isTrue();
        assertThat(state(key)).isEqualTo("COMPLETED"); assertThat(count("transactions")).isEqualTo(1);
    }

    @Test void lostOutcomeAfterNonfinancialFinalizationReplaysCommittedResponse() {
        UUID key = UUID.randomUUID();
        doAnswer(inv -> {
            var admitted = entries.admit(user, conversation, key, "synthetic");
            assertThat(entries.completeResponse(user, conversation, admitted.turn(), "committed response", null, false)).isNotNull();
            throw new AiServiceUnavailableException("controlled_lost_response_outcome");
        }).when(ai).process(any());
        var reply = send(key);
        assertThat(reply.assistantMessage().content()).isEqualTo("committed response");
        assertThat(reply.actionExecuted()).isFalse(); assertThat(state(key)).isEqualTo("COMPLETED");
        assertThat(send(key)).isEqualTo(reply); verify(ai, times(1)).process(any());
    }

    @Test void compositionFailureAfterCommitRecoversReceiptWithoutNewEffect() {
        UUID key = UUID.randomUUID(); AtomicInteger callbacks = new AtomicInteger();
        doAnswer(inv -> {
            AIServiceRequest req = inv.getArgument(0);
            if (req.execution_confirmation() == null) { interpretations.incrementAndGet(); return response(req, action()); }
            if (callbacks.getAndIncrement() == 0) throw new AiServiceUnavailableException("controlled_timeout");
            return response(req, null);
        }).when(ai).process(any());
        var first = send(key);
        assertThat(first.actionExecuted()).isTrue(); assertThat(state(key)).isEqualTo("RECEIPT_PENDING");
        expire(key);
        var recovered = send(key);
        assertThat(recovered.userMessage().id()).isEqualTo(first.userMessage().id());
        assertThat(recovered.actionExecuted()).isTrue(); assertThat(state(key)).isEqualTo("COMPLETED");
        assertThat(count("transactions")).isEqualTo(1); assertThat(interpretations.get()).isEqualTo(1);
    }

    @Test void recoveryOnlyNeverExecutesItsMessageEvenAfterReceiptCompletes() {
        UUID origin = UUID.randomUUID(), recovery = UUID.randomUUID(); AtomicInteger callbacks = new AtomicInteger();
        doAnswer(inv -> {
            AIServiceRequest req = inv.getArgument(0);
            if (req.execution_confirmation() == null) { interpretations.incrementAndGet(); return response(req, action()); }
            if (callbacks.getAndIncrement() < 2) throw new AiServiceUnavailableException("controlled_timeout");
            return response(req, null);
        }).when(ai).process(any());
        var original = send(origin); var pending = send(recovery);
        assertThat(pending.actionExecuted()).isFalse();
        var done = send(recovery); clearInvocations(ai);
        assertThat(done.actionExecuted()).isFalse();
        assertThat(done.assistantMessage().content()).startsWith("Sua nova mensagem não foi processada.");
        assertThat(send(recovery)).isEqualTo(done); verifyNoInteractions(ai);
        var originDone = send(origin);
        assertThat(originDone.actionExecuted()).isTrue();
        assertThat(originDone.userMessage().id()).isEqualTo(original.userMessage().id());
        assertThat(originDone.assistantMessage().content()).doesNotContain("Sua nova mensagem");
        assertThat(count("transactions")).isEqualTo(1); assertThat(interpretations.get()).isEqualTo(1);
    }

    @Test void keysAreScopedToAuthenticatedUserAndNeverAuthorizeForeignConversation() {
        UUID key = UUID.randomUUID(); send(key);
        UUID other = UUID.randomUUID(), otherConversation = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", other, "Synthetic", other + "@test.invalid");
        jdbc.update("insert into conversations(id,user_id) values(?,?)", otherConversation, other);
        assertThatThrownBy(() -> service.sendMessage(other, conversation, new ChatMessageRequest("synthetic"), key))
                .isInstanceOf(ResourceNotFoundException.class);
        // Same UUID in another user's namespace is independent, not a read of the first entry.
        doAnswer(inv -> new ProcessingResult(((AIServiceRequest) inv.getArgument(0)).request_id(), "1", null,
                new Decision(DecisionType.RESPONSE, null, List.of()), null, null,
                new ConversationalResponse("other user", List.of()), null, null)).when(ai).process(any());
        var reply = service.sendMessage(other, otherConversation, new ChatMessageRequest("other content"), key);
        assertThat(reply.conversationId()).isEqualTo(otherConversation);
        assertThat(jdbc.queryForObject("select count(*) from conversation_message_entries where idempotency_key=?", Integer.class, key)).isEqualTo(2);
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test void missingMalformedAndRepeatedHeadersAreRejectedAndCorsAllowsValidHeader() throws Exception {
        for (String key : new String[]{"", "not-a-uuid", "00000000-0000-1000-8000-000000000000"}) {
            mockMvc.perform(post("/conversations/" + conversation + "/messages")
                            .header("Authorization", "Bearer " + token()).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"synthetic\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("idempotency_key_invalid"));
        }
        mockMvc.perform(post("/conversations/" + conversation + "/messages")
                        .header("Authorization", "Bearer " + token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"synthetic\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("idempotency_key_required"));
        mockMvc.perform(post("/conversations/" + conversation + "/messages")
                        .header("Authorization", "Bearer " + token()).header("Idempotency-Key", UUID.randomUUID(), UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"synthetic\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(options("/conversations/" + conversation + "/messages")
                        .header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk()).andExpect(header().exists("Access-Control-Allow-Headers"));
        verifyNoInteractions(ai); assertThat(count("conversation_message_entries")).isZero();
    }

    @Test void databaseUniquenessAndForeignKeysPreserveIdentity() {
        UUID key = UUID.randomUUID(); send(key);
        UUID anotherMessage = turns.begin(user, conversation, "another synthetic message").messageId();
        assertThatThrownBy(() -> jdbc.update("""
                insert into conversation_message_entries(id,user_id,idempotency_key,conversation_id,user_message_id,
                    mode,state,interpretation_request_id,processing_deadline)
                select gen_random_uuid(),user_id,idempotency_key,conversation_id,?, 'NORMAL','PROCESSING',
                    'another-request',processing_deadline from conversation_message_entries where user_id=?
                """, anotherMessage, user))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from messages where conversation_id=?", conversation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from conversations where id=?", conversation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(send(key).actionExecuted()).isTrue(); assertThat(count("transactions")).isEqualTo(1);
    }

    @Test void selectedAndCompletedEntriesSurviveFreshSpringRuntime() {
        UUID key = UUID.randomUUID(); var admitted = entries.admit(user, conversation, key, "synthetic");
        assertThat(entries.select(user, conversation, admitted.entry().messageId(), action())).isTrue();
        ChatReplyResponse first;
        try (var runtime = coldRuntime()) {
            first = runtime.getBean(ConversationService.class).sendMessage(user, conversation, new ChatMessageRequest("synthetic"), key);
            assertThat(first.actionExecuted()).isTrue();
            verify(runtime.getBean("restartAi", AiClient.class), times(1)).process(any()); // Composition only.
        }
        try (var runtime = coldRuntime()) {
            assertThat(runtime.getBean(ConversationService.class).sendMessage(user, conversation, new ChatMessageRequest("synthetic"), key)).isEqualTo(first);
            verifyNoInteractions(runtime.getBean("restartAi", AiClient.class));
        }
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test void recoveryOnlySurvivesFreshRuntimeWithoutInterpretingNewText() {
        UUID origin = UUID.randomUUID(), recovery = UUID.randomUUID();
        doAnswer(inv -> {
            AIServiceRequest req = inv.getArgument(0);
            if (req.execution_confirmation() == null) return response(req, action());
            throw new AiServiceUnavailableException("controlled_composition_timeout");
        }).when(ai).process(any());
        send(origin);
        assertThat(entries.admit(user, conversation, recovery, "synthetic").entry().recovering()).isTrue();
        try (var runtime = coldRuntime()) {
            var reply = runtime.getBean(ConversationService.class).sendMessage(user, conversation, new ChatMessageRequest("synthetic"), recovery);
            assertThat(reply.actionExecuted()).isFalse();
            assertThat(reply.assistantMessage().content()).startsWith("Sua nova mensagem não foi processada.");
            assertThat(runtime.getBean(ConversationService.class).sendMessage(user, conversation, new ChatMessageRequest("synthetic"), recovery)).isEqualTo(reply);
            verify(runtime.getBean("restartAi", AiClient.class), times(1)).process(any());
        }
        assertThat(count("transactions")).isEqualTo(1);
    }

    private org.springframework.context.ConfigurableApplicationContext coldRuntime() {
        return new SpringApplicationBuilder(JuntaAiApplication.class, RestartFakes.class)
                .web(WebApplicationType.SERVLET).profiles("local")
                .properties("server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off",
                        "logging.level.root=WARN", "management.health.mail.enabled=false")
                .run("--server.port=0", "--server.address=127.0.0.1");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartFakes {
        @Bean @Primary AiClient restartAi() {
            AiClient transport = mock(AiClient.class);
            doAnswer(inv -> {
                AIServiceRequest req = inv.getArgument(0);
                assertThat(req.execution_confirmation()).isNotNull(); // A cold runtime must not reinterpret.
                return response(req, null);
            }).when(transport).process(any());
            return transport;
        }
    }
}
