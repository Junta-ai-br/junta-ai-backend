package br.com.juntaai.service;

import br.com.juntaai.dto.conversation.ChatReplyResponse;
import br.com.juntaai.exception.*;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageEntryStore {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ActionExecutionRepository executions;
    private final MessageEntryRepository entries;
    private final ConversationTurnStore turns;
    private final MessageProcessingWindow window;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final InitialCategoryProvisioner initialCategories;

    public record Admission(MessageEntryRepository.Entry entry, ConversationTurnStore.Turn turn, boolean fresh) {}

    @Transactional
    public Admission admit(UUID user, UUID conversationId, UUID key, String content) {
        return admit(user, conversationId, key, content, null);
    }

    @Transactional
    public Admission admit(UUID user, UUID conversationId, UUID key, String content, QuickReplySelection selection) {
        var conversation = conversations.findLockedByIdAndUserId(conversationId, user)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa"));
        var entry = entries.byKey(user, key);
        boolean fresh = false;
        if (selection != null) {
            validateSelection(conversationId, entry == null ? conversation.getPendingState() : entry.pendingBefore(), selection);
            if (!content.equals(selection.label())) throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_stale");
            if (entry == null) {
                // The owned conversation is locked. Late callbacks cannot select expired entries.
                // Never expire a selected action or a receipt awaiting composition.
                jdbc.update("""
                        update conversation_message_entries
                        set state='FAILED',failure_code='message_processing_failed',updated_at=clock_timestamp()
                        where user_id=? and conversation_id=? and pending_state_before=?
                        and state='PROCESSING' and processing_deadline <= clock_timestamp()
                        and selected_action_id is null and selected_action_json is null and receipt_action_id is null
                        """, user, conversationId, conversation.getPendingState());
            }
            if (entry == null && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from conversation_message_entries where conversation_id=?
                    and pending_state_before=? and state in ('PROCESSING','ACTION_SELECTED','RECEIPT_PENDING'))
                    """, Boolean.class, conversationId, conversation.getPendingState()))) {
                throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_in_progress");
            }
        }
        if (entry == null) {
            // Lock the user before ingress inserts acquire foreign-key locks.
            initialCategories.provision(user);
            var receipt = executions.findFirstByUserIdAndConversationIdAndCompositionPendingTrueOrderByCreatedAtAsc(user, conversationId);
            UUID messageId = UUID.randomUUID();
            fresh = entries.reserve(UUID.randomUUID(), user, conversationId, key, messageId,
                    conversation.getPendingState(), receipt.map(e -> e.getActionId()).orElse(null), window.milliseconds());
            if (fresh) {
                jdbc.update("INSERT INTO messages(id,conversation_id,role,content) VALUES (?,?,'USER',?)", messageId, conversationId, content);
            }
            entry = entries.byKey(user, key);
        }
        if (!entry.conversationId().equals(conversationId)
                || !messages.findById(entry.messageId()).orElseThrow().getContent().equals(content)) {
            throw new MessageEntryException(HttpStatus.CONFLICT, "idempotency_key_conflict");
        }
        if (!fresh && "PROCESSING".equals(entry.state()) && entries.initialExpired(entry)) {
            entries.failInitial(entry);
            entry = entries.byMessage(entry.messageId());
        }
        // Existing ingress never needs another interpretation snapshot or financial read.
        return new Admission(entry, turns.restore(entry.messageId(), entry.pendingBefore(), fresh), fresh);
    }

    private void validateSelection(UUID conversation, String pendingJson, QuickReplySelection selection) {
        try {
            var pending = mapper.readValue(pendingJson, PendingOperation.class);
            if (pending.operation() != Operation.GOAL_CONTRIBUTION
                    || !java.util.List.of("funding_source").equals(pending.unresolved_fields())
                    || pending.pending_id() == null || !pending.pending_id().equals(selection.pending_id())
                    || !conversation.toString().equals(selection.conversation_id())
                    || !conversation.toString().equals(pending.conversation_id())) throw new IllegalArgumentException();
            selection.label();
        } catch (Exception ex) { throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_stale"); }
    }

    @Transactional
    public boolean select(UUID user, UUID conversation, UUID message, ActionRequest action) {
        var entry = owned(user, conversation, message);
        if (!"PROCESSING".equals(entry.state())) return false;
        if (entries.initialExpired(entry)) { entries.failInitial(entry); return false; }
        if (action == null || action.action_id() == null || action.action_id().isBlank()
                || action.action_id().length() > 200 || action.operation() == null || action.payload() == null) {
            entries.failInitial(entry); return false;
        }
        try {
            if (!entries.select(entry, action.action_id(), mapper.writeValueAsString(action))) {
                entries.failInitial(entry); return false;
            }
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new BusinessRuleException("action_invalid"); }
        return true;
    }

    @Transactional
    public boolean failInitial(UUID user, UUID conversation, UUID message) {
        var entry = owned(user, conversation, message);
        entries.failInitial(entry);
        return "FAILED".equals(entries.byMessage(message).state());
    }

    @Transactional
    public ChatReplyResponse completeResponse(UUID user, UUID conversation, ConversationTurnStore.Turn turn,
                                              String reply, PendingOperation pending, boolean replace) {
        var entry = owned(user, conversation, turn.messageId());
        if (!"PROCESSING".equals(entry.state())) return null;
        if (entries.initialExpired(entry)) { entries.failInitial(entry); return null; }
        return turns.finish(user, conversation, turn, reply, pending, replace, null, false, false);
    }

    private MessageEntryRepository.Entry owned(UUID user, UUID conversation, UUID message) {
        conversations.findLockedByIdAndUserId(conversation, user).orElseThrow(() -> new ResourceNotFoundException("Conversa"));
        var entry = entries.byMessage(message);
        if (entry == null || !entry.userId().equals(user) || !entry.conversationId().equals(conversation)) {
            throw new BusinessRuleException("action_association_invalid");
        }
        return entry;
    }
}
