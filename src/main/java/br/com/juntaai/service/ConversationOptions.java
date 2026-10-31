package br.com.juntaai.service;

import br.com.juntaai.dto.conversation.MessageResponse;
import br.com.juntaai.entity.Message;
import br.com.juntaai.integration.ai.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/** Rebuild only the current source prompt from persisted conversation state. */
final class ConversationOptions {
    static MessageResponse response(Message message, ObjectMapper mapper) {
        List<QuickReplySelection> options = List.of();
        try {
            var pending = mapper.readValue(message.getConversation().getPendingState(), PendingOperation.class);
            if (message.getRole() == br.com.juntaai.entity.MessageRole.ASSISTANT
                    && pending.operation() == Operation.GOAL_CONTRIBUTION && pending.pending_id() != null
                    && List.of("funding_source").equals(pending.unresolved_fields())
                    && message.getId().toString().equals(pending.prompt_message_id())
                    && message.getConversation().getId().toString().equals(pending.conversation_id())) {
                options = List.of(new QuickReplySelection("source_registered", pending.pending_id(), pending.conversation_id()),
                        new QuickReplySelection("source_unregistered", pending.pending_id(), pending.conversation_id()));
            }
        } catch (Exception ignored) { /* Old messages and absent pending state have no options. */ }
        return new MessageResponse(message.getId(), message.getRole().name(), message.getContent(), message.getCreatedAt(), options);
    }
}
