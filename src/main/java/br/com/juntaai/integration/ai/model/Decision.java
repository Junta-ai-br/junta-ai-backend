package br.com.juntaai.integration.ai.model;

import java.util.List;

public record Decision(
        DecisionType type,
        String reason,
        List<String> unresolved_fields
) {}
