package br.com.juntaai.integration.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Espelha junta_ai.domain.enums.DecisionType (repositório junta-ai-ai). */
public enum DecisionType {
    @JsonProperty("response") RESPONSE,
    @JsonProperty("clarification") CLARIFICATION,
    @JsonProperty("confirmation") CONFIRMATION,
    @JsonProperty("action_request") ACTION_REQUEST,
    @JsonProperty("financial_query") FINANCIAL_QUERY
}
