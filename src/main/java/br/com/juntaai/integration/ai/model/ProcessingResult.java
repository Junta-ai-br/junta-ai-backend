package br.com.juntaai.integration.ai.model;

/** Espelha junta_ai.domain.models.ProcessingResult — resposta de /v1/conversations/process. */
public record ProcessingResult(
        String request_id,
        String contract_version,
        Interpretation interpretation,
        Decision decision,
        ActionRequest action_request,
        PendingOperation pending_operation,
        ConversationalResponse response,
        FinancialStateSnapshot financial_state,
        ResponseMetadata metadata
) {}
