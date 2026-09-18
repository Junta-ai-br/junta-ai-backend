package br.com.juntaai.integration.ai.model;

import java.util.Map;

public record Confidence(
        double overall,
        Map<String, Double> fields
) {}
