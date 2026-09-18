package br.com.juntaai.integration.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Espelha junta_ai.domain.enums.PendingState (repositório junta-ai-ai). */
public enum PendingState {
    @JsonProperty("pending_clarification") CLARIFICATION,
    @JsonProperty("pending_correction") CORRECTION,
    @JsonProperty("pending_goal") GOAL,
    @JsonProperty("pending_amount") AMOUNT
}
