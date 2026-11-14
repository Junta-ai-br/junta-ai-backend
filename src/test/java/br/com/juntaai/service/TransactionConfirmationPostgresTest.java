package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.exception.MessageEntryException;
import br.com.juntaai.exception.AiServiceUnavailableException;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Real disposable PostgreSQL, only AI output is controlled. No production data. */
@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB", matches="true")
class TransactionConfirmationPostgresTest extends AbstractIntegrationTest {
    @Autowired ConversationService chat;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessageEntryStore entries;
    @MockBean AiClient ai;
    UUID user, conversation, prompt;
    PendingOperation pending;
    volatile java.util.function.Function<AIServiceRequest, ProcessingResult> behavior;

    @BeforeEach void seed() throws Exception {
        try (var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user=UUID.randomUUID(); conversation=UUID.randomUUID(); prompt=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        jdbc.update("insert into conversations(id,user_id) values(?,?)",conversation,user);
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Synthetic confirmation','EXPENSE')",UUID.randomUUID(),user);
        pending=proposal("45.00");
        persist(pending);
        behavior=this::normal;
        when(ai.process(any())).thenAnswer(call -> behavior.apply(call.getArgument(0)));
    }
    PendingOperation proposal(String amount) {
        var data=new ParsedFinancialData(new BigDecimal(amount),"synthetic description","Synthetic confirmation",null,
            LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString(),null,Operation.EXPENSE,TemporalStatus.REALIZED,null);
        return new PendingOperation(PendingState.CONFIRMATION,Operation.EXPENSE,data,List.of("confirmation"),null,
            UUID.randomUUID().toString(),conversation.toString(),null,prompt.toString());
    }
    void persist(PendingOperation p) throws Exception {
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'ASSISTANT',?) on conflict(id) do update set content=excluded.content",prompt,conversation,FinancialPreview.financial(p)?FinancialPreview.render(p):"Synthetic goal preview");
        jdbc.update("update conversations set pending_state=? where id=?",objectMapper.writeValueAsString(p),conversation);
    }
    QuickReplySelection select(PendingOperation p,String option) { return new QuickReplySelection(option,p.pending_id(),p.conversation_id()); }
    ChatMessageRequest confirm() { return new ChatMessageRequest("visual label",select(pending,"confirm")); }
    int effects() { return jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user); }
    ProcessingResult result(AIServiceRequest r,ActionRequest action,PendingOperation p,String reason) {
        if (p!=null && "transaction_proposal".equals(reason))
            p=new PendingOperation(p.state(),p.operation(),p.known_fields(),p.unresolved_fields(),p.target_reference(),p.pending_id(),p.conversation_id(),p.resource_source(),null);
        return new ProcessingResult(r.request_id(),r.contract_version(),null,
            new Decision(action!=null?DecisionType.ACTION_REQUEST:p!=null?DecisionType.CONFIRMATION:DecisionType.RESPONSE,reason,List.of()),
            action,p,new ConversationalResponse("Synthetic response",List.of()),null,null);
    }
    ActionRequest action(ParsedFinancialData data) {
        return new ActionRequest(UUID.randomUUID().toString(),Operation.EXPENSE,data,"authoritative_state",Instant.now().plusSeconds(600));
    }
    ProcessingResult normal(AIServiceRequest r) {
        if (r.execution_confirmation()!=null) return result(r,null,null,
            r.execution_confirmation().status()==ExecutionStatus.SUCCESSFUL?"execution_confirmed":"execution_confirmation_not_matching");
        if (r.quick_reply()!=null && "cancel".equals(r.quick_reply().option_id())) return result(r,null,null,"pending_operation_cancelled");
        return result(r,action(pending.known_fields()),null,null);
    }


    @Test void informationalResponsePreservesOriginalPreviewAndDeadline() throws Exception {
        String original=jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation);
        jdbc.update("update messages set created_at=clock_timestamp()-interval '9 minutes' where id=?",prompt);
        var timestamp=jdbc.queryForObject("select created_at::text from messages where id=?",String.class,prompt);
        behavior=r->new ProcessingResult(r.request_id(),r.contract_version(),null,
            new Decision(DecisionType.RESPONSE,"product_information",List.of()),null,r.pending_operation(),
            new ConversationalResponse("Synthetic privacy information",List.of()),null,null);
        for(int i=0;i<3;i++) {
            var reply=chat.sendMessage(user,conversation,new ChatMessageRequest("politica de privacidade"),UUID.randomUUID());
            assertThat(reply.assistantMessage().quickReplies()).isEmpty();
            assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isEqualTo(original);
            assertThat(jdbc.queryForObject("select created_at::text from messages where id=?",String.class,prompt)).isEqualTo(timestamp);
        }
        behavior=this::normal;
        assertThat(chat.sendMessage(user,conversation,confirm(),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(effects()).isEqualTo(1);
    }

    record HistoricalGoal(UUID key, ChatMessageRequest input, br.com.juntaai.dto.conversation.ChatReplyResponse receipt, UUID goal) {}
    HistoricalGoal historicalGoal() throws Exception {
        UUID goal=UUID.randomUUID();
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'Viagem',100,50)",goal,user);
        var data=new ParsedFinancialData(new BigDecimal("10.00"),"synthetic",null,null,null,"Viagem",Operation.GOAL_WITHDRAWAL,TemporalStatus.REALIZED,null);
        var p=new PendingOperation(PendingState.GOAL,Operation.GOAL_WITHDRAWAL,data,List.of("confirmation"),goal.toString(),
            UUID.randomUUID().toString(),conversation.toString(),null,prompt.toString());
        persist(p);
        behavior=r->r.execution_confirmation()!=null?result(r,null,null,"execution_confirmed"):result(r,
            new ActionRequest(UUID.randomUUID().toString(),Operation.GOAL_WITHDRAWAL,data,"authoritative_state",Instant.now().plusSeconds(600)),null,null);
        UUID key=UUID.randomUUID(); var input=new ChatMessageRequest("confirmo",select(p,"confirm"));
        var receipt=chat.sendMessage(user,conversation,input,key);
        assertThat(receipt.actionExecuted()).isTrue();
        jdbc.update("update conversation_message_entries set quick_reply_json=null,created_at='2020-01-01' where user_id=? and idempotency_key=?",user,key);
        return new HistoricalGoal(key,input,receipt,goal);
    }
    @Test void legacyGoalReceiptExactSelectionCanReplayWithoutMetadata() throws Exception {
        var old=historicalGoal();
        clearInvocations(ai);
        assertThat(chat.sendMessage(user,conversation,old.input(),old.key())).isEqualTo(old.receipt());
        assertThat(chat.sendMessage(user,conversation,old.input(),old.key())).isEqualTo(old.receipt());
        verifyNoInteractions(ai);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,old.goal())).isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("select quick_reply_json from conversation_message_entries where user_id=? and idempotency_key=?",String.class,user,old.key())).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"option","pending","conversation","display","key","user","route","content","state","action","recent"})
    void legacyReplayRejectsChangedOrInsufficientEvidence(String change) throws Exception {
        var old=historicalGoal(); clearInvocations(ai);
        var q=old.input().quickReply(); UUID actor=user, route=conversation, key=old.key();
        if(change.equals("option"))q=new QuickReplySelection("cancel",q.pending_id(),q.conversation_id());
        if(change.equals("pending"))q=new QuickReplySelection("confirm",UUID.randomUUID().toString(),q.conversation_id());
        if(change.equals("conversation"))q=new QuickReplySelection("confirm",q.pending_id(),UUID.randomUUID().toString());
        if(change.equals("display"))q=new QuickReplySelection("confirm",q.pending_id(),q.conversation_id(),"altered");
        if(change.equals("key"))key=UUID.randomUUID();
        if(change.equals("user"))actor=UUID.randomUUID();
        if(change.equals("route")) {route=UUID.randomUUID();jdbc.update("insert into conversations(id,user_id) values(?,?)",route,user);}
        if(change.equals("content"))jdbc.update("update messages set content='altered' where id=?",old.receipt().userMessage().id());
        if(change.equals("state"))jdbc.update("update conversation_message_entries set mode='RECOVERY_ONLY',selected_action_id=null where user_id=? and idempotency_key=?",user,key);
        if(change.equals("action"))jdbc.update("update action_executions set action_json=replace(action_json,'10.00','11.00') where user_id=?",user);
        if(change.equals("recent"))jdbc.update("update conversation_message_entries set created_at=clock_timestamp() where user_id=? and idempotency_key=?",user,key);
        final UUID a=actor,c=route,k=key; final var input=new ChatMessageRequest("confirmo",q);
        assertThatThrownBy(()->chat.sendMessage(a,c,input,k)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(ai);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,old.goal())).isEqualByComparingTo("40.00");
    }
    @Test void missingFinancialSelectionMetadataCannotAuthorizeAnAction() {
        UUID key=UUID.randomUUID();var input=confirm();
        behavior=r->{throw new AiServiceUnavailableException("synthetic timeout");};
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input,key)).isInstanceOf(MessageEntryException.class);
        jdbc.update("update conversation_message_entries set confirmation_proposal_id=null,quick_reply_json=null,created_at='2020-01-01' where user_id=? and idempotency_key=?",user,key);
        behavior=this::normal;clearInvocations(ai);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input,key)).isInstanceOf(MessageEntryException.class);
        verifyNoInteractions(ai);assertThat(effects()).isZero();
    }



    @ParameterizedTest @ValueSource(strings={"product_information","financial_query","transaction_confirmation_required"})
    void intermediateMessagesNeverChangeFinancialBindingEvenIfAiChangesTheDraft(String reason) {
        String original=jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation);
        behavior=r->new ProcessingResult(r.request_id(),r.contract_version(),null,
            new Decision(DecisionType.CLARIFICATION,reason,List.of()),null,proposal("99.00"),
            new ConversationalResponse("Synthetic intermediate answer",List.of()),null,null);
        for(int i=0;i<3;i++) {
            var reply=chat.sendMessage(user,conversation,new ChatMessageRequest("synthetic question"),UUID.randomUUID());
            assertThat(reply.assistantMessage().quickReplies()).isEmpty();
            assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isEqualTo(original);
        }
        assertThat(effects()).isZero();
    }
    @Test void expiredPreviewCannotBeRenewedByInformation() {
        jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",prompt);
        String original=jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation);
        behavior=r->new ProcessingResult(r.request_id(),r.contract_version(),null,
            new Decision(DecisionType.RESPONSE,"product_information",List.of()),null,r.pending_operation(),
            new ConversationalResponse("Synthetic privacy",List.of()),null,null);
        assertThat(chat.sendMessage(user,conversation,new ChatMessageRequest("privacy"),UUID.randomUUID()).assistantMessage().quickReplies()).isEmpty();
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isEqualTo(original);
        clearInvocations(ai);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,confirm(),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        verifyNoInteractions(ai);assertThat(effects()).isZero();
    }
    @Test void arbitraryAssistantMessageCannotServeAsFinancialPreview() {
        jdbc.update("update messages set content='Synthetic privacy information' where id=?",prompt);
        assertThat(chat.listMessages(user,conversation).getFirst().quickReplies()).isEmpty();
        assertThatThrownBy(()->chat.sendMessage(user,conversation,confirm(),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        verifyNoInteractions(ai);assertThat(effects()).isZero();
    }
    @Test void executionRechecksPersistedSummaryAfterAdmission() {
        behavior=r->{if(r.execution_confirmation()==null)jdbc.update("update messages set content='Synthetic non-preview' where id=?",prompt);return normal(r);};
        assertThat(chat.sendMessage(user,conversation,confirm(),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(effects()).isZero();
    }
    @Test void backendPublishesValidatedSummaryInsteadOfTrustingAiResponseText() {
        behavior=r->result(r,null,proposal("30.00"),"transaction_proposal");
        var next=chat.sendMessage(user,conversation,new ChatMessageRequest("30 reais"),UUID.randomUUID());
        assertThat(next.assistantMessage().content()).contains("30,00","Synthetic confirmation","Data:","synthetic description");
        assertThat(next.assistantMessage().quickReplies()).hasSize(2);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,confirm(),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
    }


    @ParameterizedTest @ValueSource(strings={"ACTION_SELECTED","RECEIPT_PENDING"})
    void legacySelectedActionAndPendingReceiptRecoverOnlyTheirOriginalOperation(String state) throws Exception {
        var old=historicalGoal();
        var row=jdbc.queryForMap("select selected_action_id,pending_state_before from conversation_message_entries where user_id=? and idempotency_key=?",user,old.key());
        String actionId=(String)row.get("selected_action_id"),snapshot=(String)row.get("pending_state_before");
        if(state.equals("ACTION_SELECTED")) {
            String actionJson=jdbc.queryForObject("select action_json from action_executions where action_id=?",String.class,actionId);
            jdbc.update("update conversation_message_entries set state='ACTION_SELECTED',selected_action_json=?,receipt_action_id=null,response_message_id=null where user_id=? and idempotency_key=?",actionJson,user,old.key());
            jdbc.update("delete from action_executions where action_id=?",actionId);
            jdbc.update("update goals set current_amount=50 where id=?",old.goal());
            jdbc.update("update conversations set pending_state=? where id=?",snapshot,conversation);
        } else {
            jdbc.update("update conversation_message_entries set state='RECEIPT_PENDING',response_message_id=null where user_id=? and idempotency_key=?",user,old.key());
            jdbc.update("update action_executions set composition_pending=true,composition_message_id=null where action_id=?",actionId);
        }
        var recovered=chat.sendMessage(user,conversation,old.input(),old.key());
        assertThat(recovered.actionExecuted()).isTrue();
        assertThat(chat.sendMessage(user,conversation,old.input(),old.key())).isEqualTo(recovered);
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,old.goal())).isEqualByComparingTo("40.00");
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?",Integer.class,user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quick_reply_json from conversation_message_entries where user_id=? and idempotency_key=?",String.class,user,old.key())).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"amount","status","date","category","description","type"})
    void invalidAiProposalCannotPublishConsent(String field) {
        String original=jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation);
        behavior=r->{
            var d=pending.known_fields();
            var data=new ParsedFinancialData(field.equals("amount")?BigDecimal.ZERO:d.monetary_value(),
                field.equals("description")?"x".repeat(501):d.description(),field.equals("category")?"Unknown category":d.category(),null,
                field.equals("date")?"2999-01-01":d.resolved_date(),null,field.equals("type")?Operation.INCOME:d.transaction_type(),
                field.equals("status")?TemporalStatus.PLANNED:d.temporal_status(),null);
            var p=new PendingOperation(PendingState.CONFIRMATION,Operation.EXPENSE,data,List.of("confirmation"),null,
                UUID.randomUUID().toString(),conversation.toString(),null,null);
            return result(r,null,p,"transaction_proposal");
        };
        assertThatThrownBy(()->chat.sendMessage(user,conversation,new ChatMessageRequest("revise"),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isEqualTo(original);
        assertThat(effects()).isZero();
    }


    @ParameterizedTest @ValueSource(strings={"source_registered","source_unregistered","goal"})
    void legacyCompletedGoalChoiceRecoversItsStoredClarificationOnly(String option) throws Exception {
        UUID goal=UUID.randomUUID();
        jdbc.update("insert into goals(id,user_id,name,target_amount,current_amount) values(?,?,'Viagem',100,0)",goal,user);
        var data=new ParsedFinancialData(new BigDecimal("10.00"),"synthetic",null,null,null,"Viagem",Operation.GOAL_CONTRIBUTION,TemporalStatus.REALIZED,null);
        var p=new PendingOperation(PendingState.CLARIFICATION,Operation.GOAL_CONTRIBUTION,data,
            option.equals("goal")?List.of("goal_reference"):List.of("funding_source"),null,
            UUID.randomUUID().toString(),conversation.toString(),null,prompt.toString(),option.equals("goal")?Map.of(goal.toString(),"Viagem"):null);
        persist(p);
        var q=option.equals("goal")?new QuickReplySelection("goal:"+goal,p.pending_id(),p.conversation_id(),"Viagem"):select(p,option);
        UUID key=UUID.randomUUID();var admitted=entries.admit(user,conversation,key,q.label(),q);
        var stored=entries.completeResponse(user,conversation,admitted.turn(),"Synthetic historical clarification",null,false);
        jdbc.update("update conversation_message_entries set quick_reply_json=null,created_at='2020-01-01' where user_id=? and idempotency_key=?",user,key);
        assertThat(chat.sendMessage(user,conversation,new ChatMessageRequest("visual label",q),key)).isEqualTo(stored);
        verifyNoInteractions(ai);assertThat(effects()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from action_executions where user_id=?",Integer.class,user)).isZero();
    }

    @Test void confirmationExecutesExactlyOnceAndReplaysAfterExpiry() {
        UUID key=UUID.randomUUID(); var input=confirm();
        var first=chat.sendMessage(user,conversation,input,key);
        assertThat(first.actionExecuted()).isTrue(); assertThat(effects()).isEqualTo(1);
        jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",prompt);
        assertThat(chat.sendMessage(user,conversation,input,key)).isEqualTo(first);
        assertThat(effects()).isEqualTo(1);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input,UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
    }
    @Test void incomeUsesTheSameConsentGateAndFrozenPayload() throws Exception {
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Synthetic income','INCOME')",UUID.randomUUID(),user);
        var data=new ParsedFinancialData(new BigDecimal("100.00"),"synthetic income","Synthetic income",null,
            "2026-01-01",null,Operation.INCOME,TemporalStatus.REALIZED,null);
        pending=new PendingOperation(PendingState.CONFIRMATION,Operation.INCOME,data,List.of("confirmation"),null,
            UUID.randomUUID().toString(),conversation.toString(),null,prompt.toString());
        persist(pending);
        behavior=r->r.execution_confirmation()!=null?normal(r):result(r,
            new ActionRequest(UUID.randomUUID().toString(),Operation.INCOME,data,"authoritative_state",Instant.now().plusSeconds(600)),null,null);
        assertThat(chat.sendMessage(user,conversation,confirm(),UUID.randomUUID()).actionExecuted()).isTrue();
        assertThat(effects()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select transaction_date::text from transactions where user_id=?",String.class,user)).isEqualTo("2026-01-01");
    }
    @Test void failedAdmissionDoesNotReleaseItsConsentForAnotherKey() {
        behavior=r->{throw new AiServiceUnavailableException("synthetic interpretation timeout");};
        var input=confirm(); UUID key=UUID.randomUUID();
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input,key)).isInstanceOf(MessageEntryException.class);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,input,UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        assertThat(effects()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from conversation_message_entries where user_id=? and confirmation_proposal_id is not null",Integer.class,user)).isEqualTo(1);
        behavior=r->result(r,null,proposal("45.00"),"transaction_proposal");
        var newPreview=chat.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID());
        assertThat(newPreview.assistantMessage().quickReplies().getFirst().pending_id()).isNotEqualTo(pending.pending_id());
    }
    @Test void legacyAiActionWithoutSelectionCannotCreateEvenWithExactProposal() {
        assertThat(chat.sendMessage(user,conversation,new ChatMessageRequest("sim"),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(effects()).isZero();
        assertThat(jdbc.queryForObject("select receipt_json from action_executions where user_id=?",String.class,user)).contains("transaction_confirmation_required");
    }
    @Test void legacyAiActionWithoutAnyProposalCannotCreate() {
        jdbc.update("update conversations set pending_state=null where id=?",conversation);
        assertThat(chat.sendMessage(user,conversation,new ChatMessageRequest("gastei 45"),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(effects()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"amount","description","date","category","type","status"})
    void authorizedPayloadCannotBeMutated(String field) {
        behavior=r->{
            if(r.execution_confirmation()!=null)return normal(r);
            var p=pending.known_fields();
            var changed=new ParsedFinancialData(field.equals("amount")?new BigDecimal("99.00"):p.monetary_value(),
                field.equals("description")?"changed":p.description(),field.equals("category")?"Other":p.category(),p.date_reference(),
                field.equals("date")?"2020-01-01":p.resolved_date(),null,field.equals("type")?Operation.INCOME:p.transaction_type(),
                field.equals("status")?TemporalStatus.PLANNED:p.temporal_status(),p.raw_temporal_reference());
            return result(r,action(changed),null,null);
        };
        assertThat(chat.sendMessage(user,conversation,confirm(),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(effects()).isZero();
    }
    @Test void oldExpiredAndForeignSelectionsAreRejectedBeforeAi() throws Exception {
        var valid=confirm();
        for(var selection:List.of(new QuickReplySelection("confirm",UUID.randomUUID().toString(),conversation.toString()),
            new QuickReplySelection("confirm",pending.pending_id(),UUID.randomUUID().toString()))) {
            assertThatThrownBy(()->chat.sendMessage(user,conversation,new ChatMessageRequest("x",selection),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        }
        jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",prompt);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,valid,UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        verifyNoInteractions(ai); assertThat(effects()).isZero();
    }
    @Test void expirationAfterAdmissionStillBlocksFinancialEffect() {
        behavior=r->{
            if(r.execution_confirmation()!=null)return normal(r);
            jdbc.update("update messages set created_at=clock_timestamp()-interval '11 minutes' where id=?",prompt);
            return normal(r);
        };
        assertThat(chat.sendMessage(user,conversation,confirm(),UUID.randomUUID()).actionExecuted()).isFalse();
        assertThat(effects()).isZero();
    }
    @Test void constraintPreventsTwoDurableReservationsForTheSameProposal() {
        chat.sendMessage(user,conversation,confirm(),UUID.randomUUID());
        UUID other=UUID.randomUUID();
        jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'USER','synthetic duplicate')",other,conversation);
        assertThatThrownBy(()->jdbc.update("""
            insert into conversation_message_entries(id,user_id,idempotency_key,conversation_id,user_message_id,mode,state,interpretation_request_id,
                processing_deadline,quick_reply_json,confirmation_proposal_id)
            values(?,?,?,?,?,'NORMAL','PROCESSING','duplicate',clock_timestamp()+interval '1 minute','{}',?)
            """,UUID.randomUUID(),user,UUID.randomUUID(),conversation,other,UUID.fromString(pending.pending_id())))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(effects()).isEqualTo(1);
    }
    @Test void cancelHasNoFinancialEffect() {
        var reply=chat.sendMessage(user,conversation,new ChatMessageRequest("x",select(pending,"cancel")),UUID.randomUUID());
        assertThat(reply.actionExecuted()).isFalse(); assertThat(effects()).isZero();
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isNull();
    }
    @Test void changedSelectionCannotReuseMessageKey() {
        UUID key=UUID.randomUUID(); chat.sendMessage(user,conversation,confirm(),key);
        assertThatThrownBy(()->chat.sendMessage(user,conversation,new ChatMessageRequest("x",select(pending,"cancel")),key)).isInstanceOf(MessageEntryException.class);
        assertThat(effects()).isEqualTo(1);
    }
    @Test void receiptRecoversAfterCompositionFailureWithoutSecondEffect() {
        behavior=r->{if(r.execution_confirmation()!=null)throw new AiServiceUnavailableException("synthetic timeout");return normal(r);};
        UUID key=UUID.randomUUID(); var input=confirm();
        assertThat(chat.sendMessage(user,conversation,input,key).actionExecuted()).isTrue();
        assertThat(effects()).isEqualTo(1);
        behavior=this::normal;
        chat.sendMessage(user,conversation,input,key);
        assertThat(effects()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select composition_pending from action_executions where user_id=?",Boolean.class,user)).isFalse();
    }
    @Test void aNewRecoveryMessageReusesTheReceiptRatherThanTheProposal() {
        behavior=r->{if(r.execution_confirmation()!=null)throw new AiServiceUnavailableException("synthetic timeout");return normal(r);};
        chat.sendMessage(user,conversation,confirm(),UUID.randomUUID());
        assertThat(effects()).isEqualTo(1);
        behavior=this::normal;
        var recovered=chat.sendMessage(user,conversation,new ChatMessageRequest("retomar"),UUID.randomUUID());
        assertThat(recovered.actionExecuted()).isFalse();assertThat(effects()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select composition_pending from action_executions where user_id=?",Boolean.class,user)).isFalse();
        assertThat(jdbc.queryForObject("select pending_state from conversations where id=?",String.class,conversation)).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"confirm","revise","cancel"})
    void confirmationAdmissionWinsAgainstCompetingMessages(String competitor) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        behavior=r->{if(r.execution_confirmation()!=null)return normal(r);entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException ex){throw new AssertionError(ex);}return normal(r);};
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->chat.sendMessage(user,conversation,confirm(),UUID.randomUUID()));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var input=competitor.equals("confirm")?confirm():competitor.equals("cancel")?new ChatMessageRequest("x",select(pending,"cancel")):new ChatMessageRequest("valor 30 reais");
            try {assertThatThrownBy(()->chat.sendMessage(user,conversation,input,UUID.randomUUID())).isInstanceOf(MessageEntryException.class);}
            finally{release.countDown();}
            assertThat(first.get(10,TimeUnit.SECONDS).actionExecuted()).isTrue();assertThat(effects()).isEqualTo(1);
        }
    }
    @ParameterizedTest @ValueSource(strings={"revise","cancel"})
    void revisionOrCancellationAdmissionWinsAgainstConfirmation(String command) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        behavior=r->{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("timeout");}catch(InterruptedException ex){throw new AssertionError(ex);}return result(r,null,command.equals("revise")?proposal("30.00"):null,command.equals("cancel")?"pending_operation_cancelled":"transaction_proposal");};
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->chat.sendMessage(user,conversation,new ChatMessageRequest(command.equals("cancel")?"cancelar":"valor 30 reais"),UUID.randomUUID()));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try {assertThatThrownBy(()->chat.sendMessage(user,conversation,confirm(),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);}
            finally{release.countDown();}
            assertThat(first.get(10,TimeUnit.SECONDS).actionExecuted()).isFalse(); assertThat(effects()).isZero();
            assertThatThrownBy(()->chat.sendMessage(user,conversation,confirm(),UUID.randomUUID())).isInstanceOf(MessageEntryException.class);
        }
    }
}
