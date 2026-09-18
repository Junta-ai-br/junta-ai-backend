package br.com.juntaai.integration.ai.model;

import java.util.List;

/** Espelha junta_ai.domain.models.Interpretation. */
public record Interpretation(
        Intent high_level_intent,
        Operation operation,
        ParsedFinancialData data,
        Confidence confidence,
        List<String> unresolved_fields
) {}
