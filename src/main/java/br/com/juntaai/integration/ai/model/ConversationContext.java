package br.com.juntaai.integration.ai.model;

import java.util.List;
import java.util.Map;

public record ConversationContext(
        List<String> recent_messages,
        Operation last_operation,
        Map<String, Object> metadata
) {
    public static ConversationContext of(List<String> recentMessages) {
        return new ConversationContext(recentMessages, null, Map.of());
    }
}
