package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.*;
import br.com.juntaai.exception.*;
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
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Two requested PR edge cases only. Production clock/code/authorization are unchanged. */
@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB", matches="true")
class TransactionConfirmationEdgePostgresTest extends AbstractIntegrationTest {
    static final ZoneId BUSINESS=ZoneId.of("America/Sao_Paulo");
    @Autowired ConversationService chat;
    @Autowired MessageEntryStore entries;
    @Autowired ActionExecutionService executor;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockBean AiClient ai;
    UUID user,conversation;
    TransactionConsentFixture.Preview preview;
    final AtomicInteger interpretations=new AtomicInteger();
    final AtomicInteger compositions=new AtomicInteger();
    volatile Function<AIServiceRequest,ProcessingResult> route;

    @BeforeEach void seed() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user=UUID.randomUUID();conversation=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        jdbc.update("insert into conversations(id,user_id) values(?,?)",conversation,user);
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Synthetic edge','BOTH')",UUID.randomUUID(),user);
        preview=publish("45.00",Operation.EXPENSE);
        route=this::normal;
        when(ai.process(any())).thenAnswer(call->{
            AIServiceRequest r=call.getArgument(0);
            if(r.execution_confirmation()==null)interpretations.incrementAndGet();else compositions.incrementAndGet();
            return route.apply(r);
        });
    }
    ActionRequest action(ParsedFinancialData data,Operation operation) {
        return new ActionRequest(UUID.randomUUID().toString(),operation,data,"authoritative_state",Instant.now().plusSeconds(600));
    }
    TransactionConsentFixture.Preview publish(String amount,Operation kind) {
        var data=new ParsedFinancialData(new BigDecimal(amount),"synthetic frozen description","Synthetic edge","hoje",
            "2020-01-01",null,kind,TemporalStatus.REALIZED,"hoje");
        return TransactionConsentFixture.publish(jdbc,objectMapper,conversation,action(data,kind));
    }
    ChatMessageRequest input(TransactionConsentFixture.Preview p) { return new ChatMessageRequest("Confirmar",p.selection()); }
    ProcessingResult response(AIServiceRequest r,ActionRequest action,String reason) {
        return new ProcessingResult(r.request_id(),r.contract_version(),null,
            new Decision(action==null?DecisionType.RESPONSE:DecisionType.ACTION_REQUEST,reason,List.of()),action,null,
            new ConversationalResponse("Synthetic previous operation receipt",List.of()),null,null);
    }
    ProcessingResult normal(AIServiceRequest r) {
        return r.execution_confirmation()!=null?response(r,null,"execution_confirmed"):
            response(r,action(r.pending_operation().known_fields(),r.pending_operation().operation()),null);
    }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where user_id=?",Integer.class,user); }
    String state() { return jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation); }
    String receiptPending() {
        route=r->{if(r.execution_confirmation()!=null)throw new AiServiceUnavailableException("synthetic composition timeout");return normal(r);};
        assertThat(chat.sendMessage(user,conversation,input(preview),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(count("transactions")).isEqualTo(1);
        return jdbc.queryForObject("select action_id from action_executions where user_id=? and composition_pending",String.class,user);
    }
    void assertRecovery(UUID key,String receipt,TransactionConsentFixture.Preview selected) throws Exception {
        var row=jdbc.queryForMap("select * from conversation_message_entries where user_id=? and idempotency_key=?",user,key);
        assertThat(row.get("conversation_id")).isEqualTo(conversation);
        assertThat(row.get("mode")).isEqualTo("RECOVERY_ONLY");
        assertThat(row.get("receipt_action_id")).isEqualTo(receipt);
        assertThat(row.get("selected_action_id")).isNull();
        assertThat(row.get("confirmation_proposal_id")).isNull();
        assertThat(objectMapper.readTree((String)row.get("quick_reply_json"))).isEqualTo(objectMapper.valueToTree(selected.selection()));
    }
    @Test void simultaneousConfirmationsWithDifferentKeysOnlyRecoverTheOriginalReceipt() throws Exception {
        String old=receiptPending();UUID aKey=UUID.randomUUID(),bKey=UUID.randomUUID();
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        route=r->{
            assertThat(r.execution_confirmation()).isNotNull();
            assertThat(r.pending_action().action_id()).isEqualTo(old);
            assertThat(r.pending_action().payload()).isEqualTo(preview.pending().known_fields());
            assertThat(r.quick_reply()).isNull();assertThat(r.pending_operation()).isNull();
            assertThat(r.user_message()).isEqualTo("confirmo");
            entered.countDown();try {assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
            catch(InterruptedException ex){throw new AssertionError(ex);}return normal(r);
        };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->chat.sendMessage(user,conversation,input(preview),aKey));
            var b=pool.submit(()->chat.sendMessage(user,conversation,input(preview),bKey));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertRecovery(aKey,old,preview);assertRecovery(bKey,old,preview);
                assertThat(count("transactions")).isEqualTo(1);
            } finally {release.countDown();}
            var first=a.get(10,TimeUnit.SECONDS);var second=b.get(10,TimeUnit.SECONDS);
            assertThat(first.actionExecuted()).isFalse();assertThat(second.actionExecuted()).isFalse();
            assertThat(first.assistantMessage().id()).isEqualTo(second.assistantMessage().id());
            assertThat(first.assistantMessage().content()).contains("Synthetic previous operation receipt");
            assertThat(first.assistantMessage().quickReplies()).isEmpty();
            assertThat(chat.sendMessage(user,conversation,input(preview),aKey)).isEqualTo(first);
            assertThat(chat.sendMessage(user,conversation,input(preview),bKey)).isEqualTo(second);
        }
        assertThat(interpretations.get()).isEqualTo(1);assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("action_executions")).isEqualTo(1);assertThat(state()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from conversation_message_entries where user_id=? and confirmation_proposal_id is not null",Integer.class,user)).isEqualTo(1);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input(preview),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
    }
    @Test void newerProposalIsPreservedWhileTheOldReceiptIsRecoveredAndNeedsItsOwnReservation() throws Exception {
        String old=receiptPending();
        // Deliberately seed a newer persisted proposal: also exercise the late-composition preservation guard.
        var newer=publish("30.00",Operation.INCOME);String snapshot=state();UUID recovery=UUID.randomUUID();
        route=this::normal;
        var recovered=chat.sendMessage(user,conversation,input(newer),recovery);
        assertThat(recovered.actionExecuted()).isFalse();assertRecovery(recovery,old,newer);
        assertThat(state()).isEqualTo(snapshot);assertThat(count("transactions")).isEqualTo(1);
        assertThat(interpretations.get()).isEqualTo(1);
        assertThat(chat.sendMessage(user,conversation,input(newer),recovery)).isEqualTo(recovered);
        assertThat(count("transactions")).isEqualTo(1);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input(preview),recovery)).isInstanceOf(MessageEntryException.class);
        assertThatThrownBy(()->chat.sendMessage(UUID.randomUUID(),conversation,input(newer),UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        UUID other=UUID.randomUUID();jdbc.update("insert into conversations(id,user_id) values(?,?)",other,user);
        assertThatThrownBy(()->chat.sendMessage(user,other,input(newer),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        var foreign=new QuickReplySelection("confirm",newer.pending().pending_id(),UUID.randomUUID().toString());
        assertThatThrownBy(()->chat.sendMessage(user,conversation,new ChatMessageRequest("Confirmar",foreign),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        UUID confirmation=UUID.randomUUID();var committed=chat.sendMessage(user,conversation,input(newer),confirmation);
        assertThat(committed.actionExecuted()).isTrue();assertThat(interpretations.get()).isEqualTo(2);
        assertThat(count("transactions")).isEqualTo(2);assertThat(count("action_executions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select confirmation_proposal_id from conversation_message_entries where user_id=? and idempotency_key=?",UUID.class,user,confirmation)).isEqualTo(UUID.fromString(newer.pending().pending_id()));
        assertThat(chat.sendMessage(user,conversation,input(newer),confirmation)).isEqualTo(committed);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input(newer),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        assertThat(count("transactions")).isEqualTo(2);
    }
    @Test void recoveryCannotSelectOrExecuteAnotherActionEvenWithUnexpectedAiOutput() throws Exception {
        String old=receiptPending();String snapshot=state();UUID key=UUID.randomUUID();
        var admitted=entries.admit(user,conversation,key,"confirmo",preview.selection());
        var rogue=action(preview.pending().known_fields(),Operation.EXPENSE);
        assertThat(admitted.entry().recovering()).isTrue();assertThat(entries.select(user,conversation,admitted.entry().messageId(),rogue)).isFalse();
        assertThatThrownBy(()->executor.execute(user,conversation,admitted.entry().messageId(),admitted.entry().requestId(),rogue,snapshot)).isInstanceOf(BusinessRuleException.class).hasMessage("action_entry_mismatch");
        route=r->response(r,rogue,null);
        for(UUID current:List.of(key,UUID.randomUUID())) {
            assertThat(chat.sendMessage(user,conversation,input(preview),current).actionExecuted()).isFalse();
            assertRecovery(current,old,preview);
        }
        assertThat(state()).isEqualTo(snapshot);assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("action_executions")).isEqualTo(1);assertThat(interpretations.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select composition_pending from action_executions where action_id=?",Boolean.class,old)).isTrue();
        route=this::normal;chat.sendMessage(user,conversation,input(preview),key);
        assertThat(count("transactions")).isEqualTo(1);assertThat(state()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"UTC","America/Sao_Paulo"})
    void postgresTimeZoneDoesNotChangeFreshnessOrStoredInstant(String zone) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            jdbc.execute("SET LOCAL TIME ZONE '"+zone+"'");
            UUID prompt=UUID.fromString(preview.pending().prompt_message_id());
            jdbc.update("update messages set created_at=clock_timestamp()-interval '9 minutes' where id=?",prompt);
            Instant original=jdbc.queryForObject("select created_at from messages where id=?",java.sql.Timestamp.class,prompt).toInstant();
            assertThat(FinancialPreview.validPrompt(jdbc,conversation,preview.pending())).isTrue();
            jdbc.execute("SET LOCAL TIME ZONE '"+(zone.equals("UTC")?"America/Sao_Paulo":"UTC")+"'");
            assertThat(jdbc.queryForObject("select created_at from messages where id=?",java.sql.Timestamp.class,prompt).toInstant()).isEqualTo(original);
            assertThat(FinancialPreview.validPrompt(jdbc,conversation,preview.pending())).isTrue();
            jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",prompt);
            assertThat(FinancialPreview.validPrompt(jdbc,conversation,preview.pending())).isFalse();
            status.setRollbackOnly();
        });
    }
    @ParameterizedTest @ValueSource(strings={"expense","income"})
    void midnightConfirmationAndExecutedReplayPreserveEveryAuthorizedField(String kind) {
        Operation op=kind.equals("income")?Operation.INCOME:Operation.EXPENSE;
        var before=LocalDate.of(2020,1,1);var after=before.plusDays(1);
        try(var calendar=mockStatic(LocalDate.class,CALLS_REAL_METHODS)) {
            calendar.when(()->LocalDate.now(BUSINESS)).thenReturn(before);
            preview=publish("45.00",op);var authorized=preview.pending().known_fields();
            String displayed=jdbc.queryForObject("select content from messages where id=?",String.class,UUID.fromString(preview.pending().prompt_message_id()));
            calendar.when(()->LocalDate.now(BUSINESS)).thenReturn(after);
            assertThat(FinancialPreview.render(preview.pending())).isEqualTo(displayed);
            UUID key=UUID.randomUUID();var committed=chat.sendMessage(user,conversation,input(preview),key);
            assertThat(committed.actionExecuted()).isTrue();
            var tx=jdbc.queryForMap("select amount,description,type,transaction_date from transactions where user_id=?",user);
            assertThat((BigDecimal)tx.get("amount")).isEqualByComparingTo(authorized.monetary_value());
            assertThat(tx.get("description")).isEqualTo(authorized.description());assertThat(tx.get("type")).isEqualTo(op.name());
            assertThat(tx.get("transaction_date").toString()).isEqualTo(authorized.resolved_date());
            assertThat(jdbc.queryForObject("select c.name from transactions t join categories c on c.id=t.category_id where t.user_id=?",String.class,user)).isEqualTo(authorized.category());
            assertThat(jdbc.queryForObject("select action_json from action_executions where user_id=?",String.class,user)).contains("2020-01-01");
            jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",UUID.fromString(preview.pending().prompt_message_id()));
            calendar.when(()->LocalDate.now(BUSINESS)).thenReturn(after.plusDays(1));
            assertThat(chat.sendMessage(user,conversation,input(preview),key)).isEqualTo(committed);
            assertThat(count("transactions")).isEqualTo(1);
        }
    }
    @Test void expiredPreviewAtBusinessMidnightCannotSilentlyChangeItsFinancialDate() {
        var before=LocalDate.of(2020,1,1);var after=before.plusDays(1);
        try(var calendar=mockStatic(LocalDate.class,CALLS_REAL_METHODS)) {
            calendar.when(()->LocalDate.now(BUSINESS)).thenReturn(before);
            preview=publish("45.00",Operation.EXPENSE);String snapshot=state();
            jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",UUID.fromString(preview.pending().prompt_message_id()));
            calendar.when(()->LocalDate.now(BUSINESS)).thenReturn(after);
            assertThatThrownBy(()->chat.sendMessage(user,conversation,input(preview),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
            verifyNoInteractions(ai);assertThat(count("transactions")).isZero();assertThat(state()).isEqualTo(snapshot);
            assertThat(preview.pending().known_fields().resolved_date()).isEqualTo("2020-01-01");
        }
    }
}
