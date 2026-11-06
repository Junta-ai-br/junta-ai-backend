package br.com.juntaai.integration.ai.model;

import java.util.List;

/** Espelha junta_ai.domain.models.PendingOperation. */
public record PendingOperation(
        PendingState state,
        Operation operation,
        ParsedFinancialData known_fields,
        List<String> unresolved_fields,
        String target_reference,
        String pending_id,
        String conversation_id,
        String resource_source,
        String prompt_message_id,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        java.util.Map<String, String> goal_candidates
) {
    public PendingOperation(PendingState state, Operation operation, ParsedFinancialData known_fields,
            List<String> unresolved_fields, String target_reference, String pending_id, String conversation_id,
            String resource_source, String prompt_message_id) {
        this(state, operation, known_fields, unresolved_fields, target_reference, pending_id, conversation_id, resource_source, prompt_message_id, null);
    }
    public PendingOperation(PendingState state, Operation operation, ParsedFinancialData known_fields,
                            List<String> unresolved_fields, String target_reference) {
        this(state, operation, known_fields, unresolved_fields, target_reference, null, null, null, null);
    }
    public PendingOperation withPrompt(String messageId) {
        String currentPromptId = pending_id == null ? null : java.util.UUID.randomUUID().toString();
        return new PendingOperation(state, operation, known_fields, unresolved_fields, target_reference,
                currentPromptId, conversation_id, resource_source, messageId, goal_candidates);
    }
}
