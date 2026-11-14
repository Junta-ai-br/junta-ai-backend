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

    public String pendingBefore(UUID message) {
        var entry = entries.byMessage(message);
        return entry == null ? null : entry.pendingBefore();
    }

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
        // Serialize confirmation, revision and cancellation of the SAME proposal.
        // This lock is held only during admission, never during a remote AI call.
        var currentPending = conversation.getPendingState() == null ? null : mapperPending(conversation.getPendingState());
        boolean recoveringReceipt = entry == null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM action_executions WHERE user_id=? AND conversation_id=? AND composition_pending)
                """, Boolean.class, user, conversationId));
        if (entry == null && currentPending != null && currentPending.state() == PendingState.CONFIRMATION) {
            jdbc.update("""
                    UPDATE conversation_message_entries SET state='FAILED',failure_code='message_processing_failed',updated_at=clock_timestamp()
                    WHERE conversation_id=? AND pending_state_before=? AND state='PROCESSING'
                    AND processing_deadline <= clock_timestamp() AND selected_action_id IS NULL AND receipt_action_id IS NULL
                    """, conversationId, conversation.getPendingState());
        }
        if (entry == null && !recoveringReceipt && currentPending != null && currentPending.state() == PendingState.CONFIRMATION
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM conversation_message_entries WHERE conversation_id=?
                    AND pending_state_before=? AND state IN ('PROCESSING','ACTION_SELECTED','RECEIPT_PENDING'))
                    """, Boolean.class, conversationId, conversation.getPendingState()))) {
            throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_in_progress");
        }
        boolean legacyReplay = entry != null && selection != null && entries.selectionJson(entry.messageId()) == null
                && legacySelection(entry, user, conversationId, content, selection);
        if (entry != null && !sameSelection(entries.selectionJson(entry.messageId()), selection) && !legacyReplay) {
            throw new MessageEntryException(HttpStatus.CONFLICT, "idempotency_key_conflict");
        }

        if (selection != null) {
            validateSelection(user, conversationId, entry == null ? conversation.getPendingState() : entry.pendingBefore(), selection);
            if (!content.equals(selection.label())) throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_stale");
            if (entry == null && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM messages WHERE id=? AND conversation_id=? AND created_at > clock_timestamp() - interval '10 minutes')",
                    Boolean.class, UUID.fromString(mapperPending(conversation.getPendingState()).prompt_message_id()), conversationId))) {
                throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_stale");
            }
            if (entry == null && FinancialPreview.financial(currentPending) && currentPending.state()==PendingState.CONFIRMATION
                    && !FinancialPreview.validPrompt(jdbc, conversationId, currentPending))
                throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_stale");
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
            if (entry == null && (!recoveringReceipt || currentPending == null || currentPending.state() != PendingState.CONFIRMATION)
                    && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from conversation_message_entries where conversation_id=?
                    and pending_state_before=? and state in ('PROCESSING','ACTION_SELECTED','RECEIPT_PENDING'))
                    """, Boolean.class, conversationId, conversation.getPendingState()))) {
                throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_in_progress");
            }
        }
        UUID confirmingProposal = null;
        if (entry == null && !recoveringReceipt && selection != null && "confirm".equals(selection.option_id())
                && currentPending != null && currentPending.state() == PendingState.CONFIRMATION) {
            confirmingProposal = UUID.fromString(selection.pending_id());
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM conversation_message_entries
                    WHERE user_id=? AND conversation_id=? AND confirmation_proposal_id=?)
                    """, Boolean.class, user, conversationId, confirmingProposal))) {
                throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_stale");
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
                entries.bindSelection(messageId, selection == null ? null : writeSelection(selection), confirmingProposal);
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

    /** Recovery only: never interpret another action or manufacture historical financial consent. */
    private boolean legacySelection(MessageEntryRepository.Entry entry, UUID user, UUID conversation,
                                    String content, QuickReplySelection selection) {
        try {
            if (!entry.userId().equals(user) || !entry.conversationId().equals(conversation) || entry.recovering()
                    || !java.util.Set.of("COMPLETED","RECEIPT_PENDING","ACTION_SELECTED").contains(entry.state())
                    || !messages.findById(entry.messageId()).orElseThrow().getContent().equals(content)
                    || !content.equals(selection.label()) || entries.confirmedProposal(entry.messageId()) != null
                    || !Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM conversation_message_entries e
                        WHERE e.user_message_id=? AND e.created_at <
                        (SELECT installed_on FROM flyway_schema_history WHERE version='15' AND success))
                        """,Boolean.class,entry.messageId()))) return false;
            var pending=mapperPending(entry.pendingBefore());
            if (pending.operation()!=Operation.GOAL_CONTRIBUTION && pending.operation()!=Operation.GOAL_WITHDRAWAL) return false;
            if (!ConversationOptions.choices(pending).contains(selection) || pending.prompt_message_id()==null
                    || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM messages WHERE id=? AND conversation_id=? AND role='ASSISTANT')",
                        Boolean.class,UUID.fromString(pending.prompt_message_id()),conversation))) return false;
            validateSelection(user,conversation,entry.pendingBefore(),selection);
            if (entry.actionId()==null && entry.receiptId()==null) {
                return "COMPLETED".equals(entry.state()) && entry.responseId()!=null
                    && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM messages WHERE id=? AND conversation_id=? AND role='ASSISTANT')",
                        Boolean.class,entry.responseId(),conversation));
            }
            ActionRequest action;
            if(entry.receiptId()!=null) {
                var receipt=executions.findByActionId(entry.receiptId()).orElseThrow();
                if(!receipt.getUserId().equals(user) || !receipt.getConversationId().equals(conversation)
                        || !receipt.getUserMessageId().equals(entry.messageId()) || receipt.getReceiptJson()==null
                        || !receipt.getActionId().equals(entry.actionId()) || !receipt.getRequestId().equals(entry.requestId())
                        || !java.util.Objects.equals(receipt.getPendingStateBefore(),entry.pendingBefore())) return false;
                action=mapper.readValue(receipt.getActionJson(),ActionRequest.class);
            } else action=mapper.readValue(entry.actionJson(),ActionRequest.class);
            return "confirm".equals(selection.option_id()) && pending.state()==PendingState.GOAL
                && action.action_id().equals(entry.actionId()) && action.operation()==pending.operation()
                && mapper.valueToTree(action.payload()).equals(mapper.valueToTree(pending.known_fields()));
        } catch(Exception ex) { return false; }
    }

    private String writeSelection(QuickReplySelection selection) {
        try { return mapper.writeValueAsString(selection); }
        catch (Exception ex) { throw new BusinessRuleException("quick_reply_invalid"); }
    }

    private boolean sameSelection(String stored, QuickReplySelection selection) {
        if (stored == null || selection == null) return stored == null && selection == null;
        try { return mapper.readTree(stored).equals(mapper.valueToTree(selection)); }
        catch (Exception ex) { return false; }
    }

    private PendingOperation mapperPending(String json) {
        try { return mapper.readValue(json, PendingOperation.class); }
        catch (Exception ex) { throw new MessageEntryException(HttpStatus.CONFLICT, "quick_reply_stale"); }
    }

    private void validateSelection(UUID user, UUID conversation, String pendingJson, QuickReplySelection selection) {
        try {
            var pending = mapper.readValue(pendingJson, PendingOperation.class);
            if (ConversationOptions.choices(pending).stream().noneMatch(option -> option.option_id().equals(selection.option_id()))
                    || pending.prompt_message_id() == null
                    || pending.pending_id() == null || !pending.pending_id().equals(selection.pending_id())
                    || !conversation.toString().equals(selection.conversation_id())
                    || !conversation.toString().equals(pending.conversation_id())) throw new IllegalArgumentException();
            selection.label();
            if (selection.option_id().startsWith("goal:")) {
                String actual = jdbc.queryForObject("SELECT name FROM goals WHERE id=? AND user_id=?", String.class,
                        UUID.fromString(selection.option_id().substring(5)), user);
                if (!java.util.Objects.equals(actual, pending.goal_candidates().get(selection.option_id().substring(5)))) throw new IllegalArgumentException();
            }
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
