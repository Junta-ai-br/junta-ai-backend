package br.com.juntaai.integration.ai.model;

import java.util.List;

public record ConversationalResponse(
        String message,
        List<String> claims
) {}
