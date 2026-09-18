package br.com.juntaai.dto.conversation;

import java.util.UUID;

public record ChatReplyResponse(
        UUID conversationId,
        MessageResponse userMessage,
        MessageResponse assistantMessage,
        boolean actionExecuted
) {}
