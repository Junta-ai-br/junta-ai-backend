package br.com.juntaai.integration.ai.model;

import java.time.Instant;

/** Espelha junta_ai.domain.models.ActionRequest — a ação que a IA propõe. */
public record ActionRequest(
        String action_id,
        Operation operation,
        ParsedFinancialData payload,
        String expected_result,
        Instant expires_at
) {}
