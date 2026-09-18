package br.com.juntaai.integration.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Espelha junta_ai.domain.enums.TemporalStatus (repositório junta-ai-ai). */
public enum TemporalStatus {
    @JsonProperty("realized") REALIZED,
    @JsonProperty("planned") PLANNED,
    @JsonProperty("estimated") ESTIMATED,
    @JsonProperty("future") FUTURE,
    @JsonProperty("unresolved") UNRESOLVED
}
