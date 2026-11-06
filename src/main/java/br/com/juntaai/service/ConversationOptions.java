package br.com.juntaai.service;

import br.com.juntaai.dto.conversation.MessageResponse;
import br.com.juntaai.entity.Message;
import br.com.juntaai.integration.ai.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Rebuild only the current source prompt from persisted conversation state. */
final class ConversationOptions {
    static List<QuickReplySelection> choices(PendingOperation pending) {
        if (pending.operation() != Operation.GOAL_CONTRIBUTION && pending.operation() != Operation.GOAL_WITHDRAWAL) return List.of();
        var fields = pending.unresolved_fields();
        var ids = List.of("funding_source").equals(fields) ? List.of("source_registered", "source_unregistered")
                : List.of("confirmation").equals(fields) ? List.of("confirm", "cancel")
                : fields != null && fields.contains("goal_reference") && pending.goal_candidates() != null
                    ? pending.goal_candidates().keySet().stream().sorted().map(id -> "goal:" + id).toList() : List.<String>of();
        return ids.stream().map(id -> new QuickReplySelection(id, pending.pending_id(), pending.conversation_id(),
                id.startsWith("goal:") ? pending.goal_candidates().get(id.substring(5)) : null)).toList();
    }

    static MessageResponse response(Message message, ObjectMapper mapper) {
        List<QuickReplySelection> options = List.of();
        try {
            var pending = mapper.readValue(message.getConversation().getPendingState(), PendingOperation.class);
            if (message.getRole() == br.com.juntaai.entity.MessageRole.ASSISTANT
                    && pending.pending_id() != null
                    && message.getCreatedAt() != null && message.getCreatedAt().isAfter(java.time.Instant.now().minusSeconds(600))
                    && message.getId().toString().equals(pending.prompt_message_id())
                    && message.getConversation().getId().toString().equals(pending.conversation_id())) {
                options = choices(pending);
            }
        } catch (Exception ignored) { /* Old messages and absent pending state have no options. */ }
        return new MessageResponse(message.getId(), message.getRole().name(), message.getContent(), message.getCreatedAt(), options);
    }
}
