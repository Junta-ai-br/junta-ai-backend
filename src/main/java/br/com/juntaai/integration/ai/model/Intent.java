package br.com.juntaai.integration.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Espelha junta_ai.domain.enums.Intent (repositório junta-ai-ai). */
public enum Intent {
    @JsonProperty("transaction") TRANSACTION,
    @JsonProperty("financial_advice") FINANCIAL_ADVICE,
    @JsonProperty("general_question") GENERAL_QUESTION
}
