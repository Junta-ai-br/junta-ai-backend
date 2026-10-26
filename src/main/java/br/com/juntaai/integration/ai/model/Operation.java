package br.com.juntaai.integration.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Espelha junta_ai.domain.enums.Operation (repositório junta-ai-ai). */
public enum Operation {
    @JsonProperty("expense") EXPENSE,
    @JsonProperty("income") INCOME,
    @JsonProperty("goal_contribution") GOAL_CONTRIBUTION,
    @JsonProperty("goal_withdrawal") GOAL_WITHDRAWAL,
    @JsonProperty("category_update") CATEGORY_UPDATE,
    @JsonProperty("correction") CORRECTION,
    @JsonProperty("recurring_confirmation") RECURRING_CONFIRMATION,
    @JsonProperty("financial_query") FINANCIAL_QUERY
}
