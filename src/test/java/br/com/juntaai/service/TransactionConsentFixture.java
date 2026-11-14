package br.com.juntaai.service;

import br.com.juntaai.integration.ai.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.UUID;

/** Synthetic, backend-owned preview/ingress rows for executor lifecycle tests.
 * No execution bypass: the real executor still verifies every persisted field.
 * Transport/admission consent is covered independently by TransactionConfirmationPostgresTest.
 */
final class TransactionConsentFixture {
    record Preview(PendingOperation pending, String json) {
        QuickReplySelection selection() { return new QuickReplySelection("confirm", pending.pending_id(), pending.conversation_id()); }
    }
    static Preview publish(JdbcTemplate jdbc, ObjectMapper mapper, UUID conversation, ActionRequest action) {
        try {
            UUID prompt=UUID.randomUUID();
            var pending=new PendingOperation(PendingState.CONFIRMATION,action.operation(),action.payload(),List.of("confirmation"),null,
                UUID.randomUUID().toString(),conversation.toString(),null,prompt.toString());
            String json=mapper.writeValueAsString(pending);
            jdbc.update("insert into messages(id,conversation_id,role,content) values(?,?,'ASSISTANT',?)",prompt,conversation,FinancialPreview.render(pending));
            jdbc.update("update conversations set pending_state=? where id=?",json,conversation);
            return new Preview(pending,json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new AssertionError(ex); }
    }
    static Preview select(JdbcTemplate jdbc, ObjectMapper mapper, UUID user, UUID conversation, UUID message, String request, ActionRequest action) {
        var preview=publish(jdbc,mapper,conversation,action);
        try {
            jdbc.update("update messages set content='confirmo' where id=?",message);
            jdbc.update("""
                insert into conversation_message_entries(id,user_id,idempotency_key,conversation_id,user_message_id,mode,state,
                    interpretation_request_id,pending_state_before,processing_deadline,selected_action_id,selected_action_json,
                    quick_reply_json,confirmation_proposal_id)
                values(?,?,?,?,?,'NORMAL','ACTION_SELECTED',?,?,clock_timestamp()+interval '1 minute',?,?,?,?)
                """,UUID.randomUUID(),user,UUID.randomUUID(),conversation,message,request,preview.json(),action.action_id(),
                mapper.writeValueAsString(action),mapper.writeValueAsString(preview.selection()),UUID.fromString(preview.pending().pending_id()));
            return preview;
        } catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new AssertionError(ex); }
    }
}
