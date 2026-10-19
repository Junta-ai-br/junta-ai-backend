package br.com.juntaai.service;
import br.com.juntaai.dto.conversation.*;
import br.com.juntaai.dto.dashboard.DashboardResponse;
import br.com.juntaai.entity.*;
import br.com.juntaai.exception.*;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ConversationTurnStore {
    private static final int RECENT_MESSAGES_LIMIT = 6;
    private static final int RECENT_TRANSACTIONS_LIMIT = 10;
    private static final int SNAPSHOT_WINDOW_DAYS = 90;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final GoalRepository goalRepository;
    private final TransactionRepository transactionRepository;
    private final ActionExecutionRepository executions;
    private final MessageEntryRepository entries;
    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;

    @Transactional
    public Turn begin(UUID userId, UUID conversationId, String content) {
        Conversation conversation = owned(userId, conversationId);
        Message message = messageRepository.saveAndFlush(Message.builder().conversation(conversation)
                .role(MessageRole.USER).content(content).build());
        return new Turn(message.getId(), response(message), conversation.getPendingState(),
                loadPendingOperation(conversation), buildContext(conversationId), buildFinancialSnapshot(userId));
    }

    @Transactional(readOnly = true)
    public Turn restore(UUID messageId, String pendingBefore, boolean initial) {
        Message message = messageRepository.findById(messageId).orElseThrow();
        Conversation conversation = message.getConversation();
        PendingOperation pending = null;
        if (initial && pendingBefore != null && !pendingBefore.isBlank()) {
            try { pending = objectMapper.readValue(pendingBefore, PendingOperation.class); }
            catch (Exception ex) { throw new BusinessRuleException("pending_state_invalid"); }
        }
        return new Turn(message.getId(), response(message), pendingBefore, pending,
                buildContext(conversation.getId()), initial ? buildFinancialSnapshot(conversation.getUser().getId()) : null);
    }

    @Transactional
    public ChatReplyResponse completed(UUID userId, UUID conversationId, UUID messageId) {
        owned(userId, conversationId);
        var entry = entries.byMessage(messageId);
        if (entry == null || !entry.userId().equals(userId) || !entry.conversationId().equals(conversationId)
                || !"COMPLETED".equals(entry.state()) || entry.responseId() == null) {
            throw new BusinessRuleException("composition_evidence_invalid");
        }
        boolean executed = false;
        if (entry.receiptId() != null) {
            var receipt = executions.findByActionId(entry.receiptId()).orElseThrow();
            if (!receipt.getUserId().equals(userId) || !receipt.getConversationId().equals(conversationId)
                    || (!entry.recovering() && !receipt.getUserMessageId().equals(messageId))) {
                throw new BusinessRuleException("action_association_invalid");
            }
            executed = !entry.recovering() && receipt.getStatus() == ExecutionStatus.SUCCESSFUL;
        }
        return new ChatReplyResponse(conversationId, response(messageRepository.findById(messageId).orElseThrow()),
                response(messageRepository.findById(entry.responseId()).orElseThrow()), executed);
    }

    @Transactional
    public ChatReplyResponse finish(UUID userId, UUID conversationId, Turn turn, String reply,
                                    PendingOperation pending, boolean replacePending,
                                    String receiptActionId, boolean compositionComplete, boolean actionExecuted) {
        Conversation conversation = owned(userId, conversationId);
        // Lock order: owned conversation, optional ingress, then receipt. No remote call here.
        var entry = entries.byMessage(turn.messageId());
        if (entry != null && (!entry.userId().equals(userId) || !entry.conversationId().equals(conversationId)
                || !Objects.equals(entry.receiptId(), receiptActionId))) {
            throw new BusinessRuleException("action_association_invalid");
        }
        ActionExecution evidence = null;
        if (receiptActionId != null) {
            evidence = executions.findByActionId(receiptActionId).orElseThrow();
            if (!evidence.getUserId().equals(userId) || !evidence.getConversationId().equals(conversationId)) {
                throw new BusinessRuleException("action_association_invalid");
            }
            if (!evidence.isCompositionPending()) {
                // Even a late timeout must return the accepted result, not a pending fallback.
                if (evidence.getCompositionMessageId() == null) {
                    throw new BusinessRuleException("composition_evidence_invalid");
                }
                Message accepted = messageRepository.findById(evidence.getCompositionMessageId()).orElseThrow();
                if (entry != null) entries.finish(entry, accepted.getId(), true);
                return new ChatReplyResponse(conversationId, turn.userMessage(), response(accepted), actionExecuted);
            }
        }
        if (replacePending && Objects.equals(conversation.getPendingState(), turn.pendingBefore())
                && (evidence == null || Objects.equals(conversation.getPendingState(), evidence.getPendingStateBefore()))) {
            savePendingOperation(conversation, pending);
        }
        Message assistant = messageRepository.saveAndFlush(Message.builder().conversation(conversation)
                .role(MessageRole.ASSISTANT).content(reply).build());
        // Replay uses the stored PostgreSQL timestamp (microsecond precision), including the first reply.
        entityManager.refresh(assistant);
        if (evidence != null && compositionComplete) {
            evidence.setCompositionMessageId(assistant.getId());
            evidence.setCompositionPending(false);
        }
        if (entry != null) entries.finish(entry, assistant.getId(), evidence == null || compositionComplete);
        return new ChatReplyResponse(conversationId, turn.userMessage(), response(assistant), actionExecuted);
    }

    private Conversation owned(UUID userId, UUID conversationId) {
        return conversationRepository.findLockedByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa"));
    }
    private MessageResponse response(Message m) {
        return new MessageResponse(m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt());
    }
    public record Turn(UUID messageId, MessageResponse userMessage, String pendingBefore,
                       PendingOperation pending, ConversationContext context, FinancialStateSnapshot snapshot) {}
    @Transactional(readOnly = true)
    public FinancialStateSnapshot buildFinancialSnapshot(UUID userId) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(SNAPSHOT_WINDOW_DAYS);

        DashboardResponse dashboard = dashboardService.summarize(userId, from, to);

        Map<String, Object> currentState = new HashMap<>();
        currentState.put("balance", dashboard.balance());
        currentState.put("totalIncome", dashboard.totalIncome());
        currentState.put("totalExpense", dashboard.totalExpense());
        currentState.put("periodFrom", from.toString());
        currentState.put("periodTo", to.toString());

        List<Transaction> recent = transactionRepository
                .findByUserIdAndTransactionDateBetween(
                        userId, from, to, PageRequest.of(0, RECENT_TRANSACTIONS_LIMIT, Sort.by("transactionDate").descending()))
                .getContent();

        List<Map<String, Object>> realizedRecords = new ArrayList<>();
        for (Transaction transaction : recent) {
            Map<String, Object> record = new HashMap<>();
            record.put("category", transaction.getCategory().getName());
            record.put("type", transaction.getType().name());
            record.put("amount", transaction.getAmount());
            record.put("description", transaction.getDescription());
            record.put("date", transaction.getTransactionDate().toString());
            realizedRecords.add(record);
        }

        Map<String, Object> goalProgress = new HashMap<>();
        for (Goal goal : goalRepository.findByUserIdOrderByDeadlineAsc(userId)) {
            Map<String, Object> progress = new HashMap<>();
            progress.put("targetAmount", goal.getTargetAmount());
            progress.put("currentAmount", goal.getCurrentAmount());
            progress.put("status", goal.getStatus().name());
            goalProgress.put(goal.getName(), progress);
        }

        return new FinancialStateSnapshot(
                null, currentState, Map.of(), realizedRecords, List.of(), goalProgress, Map.of());
    }

     private ConversationContext buildContext(UUID conversationId) {
        // Busca só as mais recentes no banco (antes carregava a conversa inteira).
        List<Message> latest = new java.util.ArrayList<>(messageRepository.findByConversationId(
                conversationId, PageRequest.of(0, RECENT_MESSAGES_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt"))));
        java.util.Collections.reverse(latest);
        List<String> recent = latest.stream()
                .map(Message::getContent)
                .toList();
        return ConversationContext.of(recent);
    }
    
    private PendingOperation loadPendingOperation(Conversation conversation) {
        String raw = conversation.getPendingState();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, PendingOperation.class);
        } catch (Exception ex) {
            throw new BusinessRuleException("pending_state_invalid");
        }
    }

    private void savePendingOperation(Conversation conversation, PendingOperation pendingOperation) {
        if (pendingOperation == null) {
            conversation.setPendingState(null);
            return;
        }
        try {
            conversation.setPendingState(objectMapper.writeValueAsString(pendingOperation));
        } catch (Exception ex) {
            throw new BusinessRuleException("pending_state_invalid");
        }
    }

}
