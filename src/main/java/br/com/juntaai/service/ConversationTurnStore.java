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
    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Turn begin(UUID userId, UUID conversationId, String content) {
        Conversation conversation = owned(userId, conversationId);
        Message message = messageRepository.saveAndFlush(Message.builder().conversation(conversation)
                .role(MessageRole.USER).content(content).build());
        return new Turn(message.getId(), response(message), conversation.getPendingState(),
                loadPendingOperation(conversation), buildContext(conversationId), buildFinancialSnapshot(userId));
    }

    @Transactional
    public ChatReplyResponse finish(UUID userId, UUID conversationId, Turn turn, String reply,
                                    PendingOperation pending, boolean replacePending,
                                    String receiptActionId, boolean compositionComplete, boolean actionExecuted) {
        Conversation conversation = owned(userId, conversationId);
        if (replacePending && Objects.equals(conversation.getPendingState(), turn.pendingBefore())) {
            savePendingOperation(conversation, pending);
        }
        if (receiptActionId != null && compositionComplete) {
            ActionExecution evidence = executions.findByActionId(receiptActionId).orElseThrow();
            if (!evidence.getUserId().equals(userId) || !evidence.getConversationId().equals(conversationId)) {
                throw new BusinessRuleException("action_association_invalid");
            }
            evidence.setCompositionPending(false);
        }
        Message assistant = messageRepository.saveAndFlush(Message.builder().conversation(conversation)
                .role(MessageRole.ASSISTANT).content(reply).build());
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
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int fromIndex = Math.max(0, messages.size() - RECENT_MESSAGES_LIMIT);
        List<String> recent = messages.subList(fromIndex, messages.size()).stream()
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
