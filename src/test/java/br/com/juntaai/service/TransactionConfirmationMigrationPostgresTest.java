package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Upgrade in a fresh, unique schema inside the disposable cluster only. */
@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB", matches="true")
class TransactionConfirmationMigrationPostgresTest extends AbstractIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationService chat;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Test void v14StructuredGoalReceiptSurvivesUpgradeAndRejectsAlteredReplay() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        String schema="goal_replay_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").target("14").load().migrate();
        UUID user=UUID.randomUUID(),conversation=UUID.randomUUID(),prompt=UUID.randomUUID(),message=UUID.randomUUID(),response=UUID.randomUUID(),goal=UUID.randomUUID(),key=UUID.randomUUID();
        var q=new br.com.juntaai.integration.ai.model.QuickReplySelection("confirm",UUID.randomUUID().toString(),conversation.toString());
        var data=new br.com.juntaai.integration.ai.model.ParsedFinancialData(new java.math.BigDecimal("10.00"),"synthetic",null,null,null,"Viagem",br.com.juntaai.integration.ai.model.Operation.GOAL_WITHDRAWAL,br.com.juntaai.integration.ai.model.TemporalStatus.REALIZED,null);
        var pending=new br.com.juntaai.integration.ai.model.PendingOperation(br.com.juntaai.integration.ai.model.PendingState.GOAL,br.com.juntaai.integration.ai.model.Operation.GOAL_WITHDRAWAL,data,java.util.List.of("confirmation"),goal.toString(),q.pending_id(),conversation.toString(),null,prompt.toString());
        String snapshot=objectMapper.writeValueAsString(pending),actionId=UUID.randomUUID().toString(),request=UUID.randomUUID().toString();
        var action=new br.com.juntaai.integration.ai.model.ActionRequest(actionId,pending.operation(),data,"authoritative_state",java.time.Instant.now().minusSeconds(600));
        var confirmation=new br.com.juntaai.integration.ai.model.ExecutionConfirmation(actionId,br.com.juntaai.integration.ai.model.ExecutionStatus.SUCCESSFUL,pending.operation(),new br.com.juntaai.integration.ai.model.FinancialStateSnapshot(null,java.util.Map.of(),java.util.Map.of(),java.util.List.of(),java.util.List.of(),java.util.Map.of(),java.util.Map.of()),null);
        jdbc.update("insert into "+schema+".users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        jdbc.update("insert into "+schema+".conversations(id,user_id) values(?,?)",conversation,user);
        jdbc.update("insert into "+schema+".goals(id,user_id,name,target_amount,current_amount) values(?,?,'Viagem',100,40)",goal,user);
        jdbc.update("insert into "+schema+".messages(id,conversation_id,role,content) values(?,?,'ASSISTANT','Synthetic original goal preview'),(?,?,'USER','confirmo'),(?,?,'ASSISTANT','Synthetic historical receipt')",prompt,conversation,message,conversation,response,conversation);
        jdbc.update("insert into "+schema+".action_executions(action_id,user_id,conversation_id,user_message_id,request_id,action_json,receipt_json,status,effect_id,pending_state_before,composition_pending,composition_message_id) values(?,?,?,?,?,?,?,'SUCCESSFUL',?,?,false,?)",
            actionId,user,conversation,message,request,objectMapper.writeValueAsString(action),objectMapper.writeValueAsString(confirmation),goal,snapshot,response);
        jdbc.update("insert into "+schema+".conversation_message_entries(id,user_id,idempotency_key,conversation_id,user_message_id,mode,state,interpretation_request_id,pending_state_before,processing_deadline,selected_action_id,receipt_action_id,response_message_id) values(?,?,?,?,?,'NORMAL','COMPLETED',?,?,clock_timestamp()-interval '1 day',?,?,?)",
            UUID.randomUUID(),user,key,conversation,message,request,snapshot,actionId,actionId,response);
        assertThat(Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load().migrate().migrationsExecuted).isEqualTo(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        for(int i=0;i<2;i++) tx.execute(status->{
            jdbc.execute("SET LOCAL search_path TO "+schema+",public");
            var recovered=chat.sendMessage(user,conversation,new br.com.juntaai.dto.conversation.ChatMessageRequest("confirmo",q),key);
            assertThat(recovered.assistantMessage().id()).isEqualTo(response);
            assertThat(recovered.actionExecuted()).isTrue();
            assertThat(jdbc.queryForObject("select current_amount from goals where id=?",java.math.BigDecimal.class,goal)).isEqualByComparingTo("40.00");
            assertThat(jdbc.queryForObject("select count(*) from action_executions",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select quick_reply_json from conversation_message_entries",String.class)).isNull();
            return null;
        });
        assertThatThrownBy(()->tx.execute(status->{
            jdbc.execute("SET LOCAL search_path TO "+schema+",public");
            return chat.sendMessage(user,conversation,new br.com.juntaai.dto.conversation.ChatMessageRequest("cancelar",new br.com.juntaai.integration.ai.model.QuickReplySelection("cancel",q.pending_id(),q.conversation_id())),key);
        })).isInstanceOf(br.com.juntaai.exception.MessageEntryException.class);
    }
    @Test void v14RowsRemainUnapprovedWhenV15IsApplied() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        String schema="confirmation_upgrade_"+UUID.randomUUID().toString().replace("-","");
        var old=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").target("14").load();
        old.migrate();
        UUID user=UUID.randomUUID(),conversation=UUID.randomUUID(),message=UUID.randomUUID();
        jdbc.update("insert into "+schema+".users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        jdbc.update("insert into "+schema+".conversations(id,user_id) values(?,?)",conversation,user);
        jdbc.update("insert into "+schema+".messages(id,conversation_id,role,content) values(?,?,'USER','legacy synthetic')",message,conversation);
        jdbc.update("insert into "+schema+".conversation_message_entries(id,user_id,idempotency_key,conversation_id,user_message_id,mode,state,interpretation_request_id,processing_deadline) values(?,?,?,?,?,'NORMAL','PROCESSING','legacy',clock_timestamp()+interval '1 minute')",
            UUID.randomUUID(),user,UUID.randomUUID(),conversation,message);
        var upgraded=Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").load();
        assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
        upgraded.validate();
        var row=jdbc.queryForMap("select quick_reply_json,confirmation_proposal_id from "+schema+".conversation_message_entries");
        assertThat(row.get("quick_reply_json")).isNull();assertThat(row.get("confirmation_proposal_id")).isNull();
    }
}
