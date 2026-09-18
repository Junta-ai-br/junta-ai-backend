package br.com.juntaai.integration.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Espelha junta_ai.domain.enums.ExecutionStatus (repositório junta-ai-ai). */
public enum ExecutionStatus {
    @JsonProperty("successful") SUCCESSFUL,
    @JsonProperty("rejected") REJECTED,
    @JsonProperty("failed") FAILED,
    @JsonProperty("unavailable") UNAVAILABLE
}
