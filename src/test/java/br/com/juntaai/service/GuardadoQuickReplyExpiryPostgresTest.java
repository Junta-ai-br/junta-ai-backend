package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.exception.MessageEntryException;
import br.com.juntaai.integration.ai.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB", matches="true")
class GuardadoQuickReplyExpiryPostgresTest extends AbstractIntegrationTest {
    @Autowired MessageEntryStore entries;
    @Autowired ActionExecutionService actions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    UUID user, conversation;
    QuickReplySelection selection;
    String pending;
    ParsedFinancialData payload;

    @BeforeEach void seed() throws Exception {
        assertThat(jdbc.queryForObject("select current_database()", String.class)).startsWith("junta_i3_disposable_");
        user = UUID.randomUUID(); conversation = UUID.randomUUID();
        var goal = UUID.randomUUID();
        payload = new ParsedFinancialData(new BigDecimal("10.00"), "synthetic", null, null, null,
                "Viagem", Operation.GOAL_CONTRIBUTION, TemporalStatus.REALIZED, null);
        selection = new QuickReplySelection("source_registered", UUID.randomUUID().toString(), conversation.toString());
        pending = mapper.writeValueAsString(new PendingOperation(PendingState.CLARIFICATION,
                Operation.GOAL_CONTRIBUTION, payload, List.of("funding_source"), goal.toString(),
                selection.pending_id(), conversation.toString(), null, null));
        jdbc.update("insert into users(id,name,email) values(?,?,?)", user, "Synthetic", user + "@test.invalid");
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'Viagem',100,0)",goal,user);
        jdbc.update("insert into conversations(id,user_id,pending_state) values(?,?,?)", conversation,user,pending);
    }
    MessageEntryStore.Admission admit(UUID key) {
        return entries.admit(user,conversation,key,selection.label(),selection);
    }
    void expire(UUID message) {
        jdbc.update("update conversation_message_entries set processing_deadline=clock_timestamp()-interval '1 second' where user_message_id=?",message);
    }
    String state(UUID message) {
        return jdbc.queryForObject("select state from conversation_message_entries where user_message_id=?",String.class,message);
    }
    void blocked(UUID key) {
        assertThatThrownBy(() -> admit(key)).isInstanceOf(MessageEntryException.class)
                .extracting("code").isEqualTo("quick_reply_in_progress");
    }
    ActionRequest action() {
        return new ActionRequest(UUID.randomUUID().toString(),Operation.GOAL_CONTRIBUTION,payload,
                "authoritative_state",Instant.now().plusSeconds(600));
    }

    @Test void expiredUnselectedEntryAllowsANewSelectionAndRejectsItsLateCallback() {
        var old = admit(UUID.randomUUID());
        expire(old.turn().messageId());
        var fresh = admit(UUID.randomUUID());
        assertThat(fresh.fresh()).isTrue();
        assertThat(state(old.turn().messageId())).isEqualTo("FAILED");
        assertThat(state(fresh.turn().messageId())).isEqualTo("PROCESSING");
        assertThat(entries.select(user,conversation,old.turn().messageId(),action())).isFalse();
        assertThat(entries.completeResponse(user,conversation,old.turn(),"late",null,true)).isNull();
    }

    @Test void aLiveSelectionStillBlocksAnotherKeyButTheSameKeyIsAReplay() {
        UUID key=UUID.randomUUID();
        var first=admit(key);
        blocked(UUID.randomUUID());
        var replay=admit(key);
        assertThat(replay.fresh()).isFalse();
        assertThat(replay.turn().messageId()).isEqualTo(first.turn().messageId());
        assertThat(state(first.turn().messageId())).isEqualTo("PROCESSING");
    }

    @Test void simultaneousRetriesAfterExpiryHaveOnlyOneAdmissionAndOneSelectableAction() throws Exception {
        var old=admit(UUID.randomUUID()); expire(old.turn().messageId());
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<MessageEntryStore.Admission> attempt=() -> {
                start.await();
                try { return admit(UUID.randomUUID()); }
                catch(MessageEntryException ex) { assertThat(ex.getCode()).isEqualTo("quick_reply_in_progress"); return null; }
            };
            var a=pool.submit(attempt); var b=pool.submit(attempt); start.countDown();
            var first=a.get(15,TimeUnit.SECONDS); var second=b.get(15,TimeUnit.SECONDS);
            assertThat((first==null) != (second==null)).isTrue();
            var winner=first==null ? second : first;
            assertThat(entries.select(user,conversation,winner.turn().messageId(),action())).isTrue();
            assertThat(entries.select(user,conversation,old.turn().messageId(),action())).isFalse();
            blocked(UUID.randomUUID());
            assertThat(jdbc.queryForObject("select count(*) from conversation_message_entries where conversation_id=? and state='ACTION_SELECTED'",Integer.class,conversation)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void expiredDeadlineNeverDiscardsASelectedActionOrReceipt(boolean receiptPending) {
        var original=admit(UUID.randomUUID());
        var action=action();
        assertThat(entries.select(user,conversation,original.turn().messageId(),action)).isTrue();
        if(receiptPending) {
            var receipt=actions.execute(user,conversation,original.turn().messageId(),original.entry().requestId(),action,pending);
            // Selecting a source cannot authorize this financial action, even through recovery.
            assertThat(receipt.confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        }
        expire(original.turn().messageId());
        String expected=receiptPending ? "RECEIPT_PENDING" : "ACTION_SELECTED";
        blocked(UUID.randomUUID());
        assertThat(state(original.turn().messageId())).isEqualTo(expected);
        assertThat(jdbc.queryForObject("select selected_action_id from conversation_message_entries where user_message_id=?",String.class,original.turn().messageId())).isEqualTo(action.action_id());
        if(receiptPending) {
            assertThat(jdbc.queryForObject("select receipt_action_id from conversation_message_entries where user_message_id=?",String.class,original.turn().messageId())).isEqualTo(action.action_id());
            assertThat(actions.recordedReceipt(user,conversation,action.action_id()).confirmation().status()).isEqualTo(ExecutionStatus.REJECTED);
        }
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isZero();
    }
}
