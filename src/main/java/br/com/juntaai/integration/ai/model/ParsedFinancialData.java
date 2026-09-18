package br.com.juntaai.integration.ai.model;

import java.math.BigDecimal;

/** Espelha junta_ai.domain.models.ParsedFinancialData. */
public record ParsedFinancialData(
        BigDecimal monetary_value,
        String description,
        String category,
        String date_reference,
        String resolved_date,
        String goal_reference,
        Operation transaction_type,
        TemporalStatus temporal_status,
        String raw_temporal_reference
) {}
