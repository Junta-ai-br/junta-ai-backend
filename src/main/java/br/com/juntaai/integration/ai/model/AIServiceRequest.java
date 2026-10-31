package br.com.juntaai.integration.ai.model;

/**
 * Espelha junta_ai.domain.models.AIServiceRequest — corpo enviado em
 * POST /v1/conversations/process. Contrato: repositório junta-ai-ai,
 * specs/001-ai-service-foundation/contracts/service-boundary.md.
 */
public record AIServiceRequest(
        String request_id,
        String contract_version,
        String conversation_id,
        String user_message,
        ConversationContext context,
        PendingOperation pending_operation,
        ActionRequest pending_action,
        FinancialStateSnapshot financial_state,
        ExecutionConfirmation execution_confirmation,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        QuickReplySelection quick_reply
) {
    public AIServiceRequest(String request_id, String contract_version, String conversation_id, String user_message,
            ConversationContext context, PendingOperation pending_operation, ActionRequest pending_action,
            FinancialStateSnapshot financial_state, ExecutionConfirmation execution_confirmation) {
        this(request_id, contract_version, conversation_id, user_message, context, pending_operation,
                pending_action, financial_state, execution_confirmation, null);
    }
}
