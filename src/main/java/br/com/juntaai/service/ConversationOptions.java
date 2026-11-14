package br.com.juntaai.service;

import br.com.juntaai.dto.conversation.MessageResponse;
import br.com.juntaai.entity.Message;
import br.com.juntaai.integration.ai.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Rebuild only the current source prompt from persisted conversation state. */
final class ConversationOptions {
    static List<QuickReplySelection> choices(PendingOperation pending) {
        if (pending.state() == PendingState.CONFIRMATION
                && (pending.operation() == Operation.EXPENSE || pending.operation() == Operation.INCOME)) {
            return List.of("confirmation").equals(pending.unresolved_fields())
                    ? List.of(new QuickReplySelection("confirm", pending.pending_id(), pending.conversation_id()),
                              new QuickReplySelection("cancel", pending.pending_id(), pending.conversation_id())) : List.of();
        }
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
        return response(message, mapper, null);
    }
    static MessageResponse response(Message message, ObjectMapper mapper,
            java.util.function.Function<java.util.UUID, String> pendingBefore) {
        String content = message.getContent();
        if (message.getRole() == br.com.juntaai.entity.MessageRole.USER && content != null
                && content.startsWith("goal:") && pendingBefore != null) {
            try {
                java.util.UUID.fromString(content.substring(5));
                var state = mapper.readValue(pendingBefore.apply(message.getId()), PendingOperation.class);
                if (state.goal_candidates() != null) content = state.goal_candidates().getOrDefault(content.substring(5), content);
            } catch (Exception ignored) { /* Literal user text without bound metadata stays unchanged. */ }
        }
        List<QuickReplySelection> options = List.of();
        try {
            var pending = mapper.readValue(message.getConversation().getPendingState(), PendingOperation.class);
            if (message.getRole() == br.com.juntaai.entity.MessageRole.ASSISTANT
                    && pending.pending_id() != null
                    && message.getCreatedAt() != null && message.getCreatedAt().isAfter(java.time.Instant.now().minusSeconds(600))
                    && message.getId().toString().equals(pending.prompt_message_id())
                    && message.getConversation().getId().toString().equals(pending.conversation_id())) {
                if (!FinancialPreview.financial(pending) || pending.state()!=PendingState.CONFIRMATION
                        || message.getContent().equals(FinancialPreview.render(pending))) options = choices(pending);
            }
        } catch (Exception ignored) { /* Old messages and absent pending state have no options. */ }
        return new MessageResponse(message.getId(), message.getRole().name(), content, message.getCreatedAt(), options);
    }
}
