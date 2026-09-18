package br.com.juntaai.integration.ai.model;

public record ResponseMetadata(
        String correlation_id,
        String stage,
        DecisionType decision,
        String outcome,
        String contract_version
) {}
