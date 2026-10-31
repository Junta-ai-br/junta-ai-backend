package br.com.juntaai.integration.ai.model;

import java.util.List;

public record ConversationalResponse(
        String message,
        List<String> claims,
        List<QuickReplySelection> quick_replies
) {
    public ConversationalResponse(String message, List<String> claims) { this(message, claims, List.of()); }
}
