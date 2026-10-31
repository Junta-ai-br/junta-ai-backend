package br.com.juntaai.dto.conversation;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String role,
        String content,
        Instant createdAt,
        java.util.List<br.com.juntaai.integration.ai.model.QuickReplySelection> quickReplies
) {
    public MessageResponse(UUID id, String role, String content, Instant createdAt) {
        this(id, role, content, createdAt, java.util.List.of());
    }
}
