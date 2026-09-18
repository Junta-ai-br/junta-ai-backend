package br.com.juntaai.integration.ai.model;

import java.util.List;

/** Espelha junta_ai.domain.models.PendingOperation. */
public record PendingOperation(
        PendingState state,
        Operation operation,
        ParsedFinancialData known_fields,
        List<String> unresolved_fields,
        String target_reference
) {}
