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
        String prompt_message_id
) {
    public PendingOperation(PendingState state, Operation operation, ParsedFinancialData known_fields,
                            List<String> unresolved_fields, String target_reference) {
        this(state, operation, known_fields, unresolved_fields, target_reference, null, null, null, null);
    }
    public PendingOperation withPrompt(String messageId) {
        String currentPromptId = java.util.List.of("funding_source").equals(unresolved_fields)
                ? java.util.UUID.randomUUID().toString() : pending_id;
        return new PendingOperation(state, operation, known_fields, unresolved_fields, target_reference,
                currentPromptId, conversation_id, resource_source, messageId);
    }
}
