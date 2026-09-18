package br.com.juntaai.service;

import br.com.juntaai.dto.conversation.ChatMessageRequest;
import br.com.juntaai.dto.conversation.ChatReplyResponse;
import br.com.juntaai.dto.conversation.ConversationResponse;
import br.com.juntaai.dto.conversation.MessageResponse;
import br.com.juntaai.dto.dashboard.DashboardResponse;
import br.com.juntaai.dto.transaction.TransactionRequest;
import br.com.juntaai.entity.Category;
import br.com.juntaai.entity.Conversation;
import br.com.juntaai.entity.Goal;
import br.com.juntaai.entity.Message;
import br.com.juntaai.entity.MessageRole;
import br.com.juntaai.entity.Transaction;
import br.com.juntaai.entity.TransactionType;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.integration.ai.AiClient;
import br.com.juntaai.integration.ai.model.ActionRequest;
import br.com.juntaai.integration.ai.model.AIServiceRequest;
import br.com.juntaai.integration.ai.model.ConversationContext;
import br.com.juntaai.integration.ai.model.ExecutionConfirmation;
import br.com.juntaai.integration.ai.model.ExecutionStatus;
import br.com.juntaai.integration.ai.model.FinancialStateSnapshot;
import br.com.juntaai.integration.ai.model.Operation;
import br.com.juntaai.integration.ai.model.ParsedFinancialData;
import br.com.juntaai.integration.ai.model.PendingOperation;
import br.com.juntaai.integration.ai.model.ProcessingResult;
import br.com.juntaai.repository.CategoryRepository;
import br.com.juntaai.repository.ConversationRepository;
import br.com.juntaai.repository.GoalRepository;
import br.com.juntaai.repository.MessageRepository;
import br.com.juntaai.repository.TransactionRepository;
import br.com.juntaai.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orquestra a experiência conversacional entre Backend e o serviço de IA
 * (junta-ai-ai), seguindo o contrato de
 * specs/001-ai-service-foundation/contracts/service-boundary.md.
 *
 * A IA é stateless: cada chamada precisa receber o pending_operation da
 * rodada anterior de volta. O Backend guarda esse estado em
 * Conversation.pendingState (serializado em JSON) e é sempre quem decide
 * se uma ActionRequest proposta pela IA é executada de fato — a IA nunca
 * escreve no banco.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int RECENT_MESSAGES_LIMIT = 6;
    private static final int RECENT_TRANSACTIONS_LIMIT = 10;
    private static final int SNAPSHOT_WINDOW_DAYS = 90;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final GoalRepository goalRepository;
    private final TransactionRepository transactionRepository;
    private final AiClient aiClient;
    private final TransactionService transactionService;
    private final GoalService goalService;
    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ConversationResponse createConversation(UUID userId, String title) {
        User user = userRepository.getReferenceById(userId);
        Conversation conversation = Conversation.builder().user(user).title(title).build();
        conversationRepository.save(conversation);
        return toResponse(conversation);
    }

    public List<ConversationResponse> listForUser(UUID userId) {
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<MessageResponse> listMessages(UUID userId, UUID conversationId) {
        findOwnedOrThrow(userId, conversationId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ChatReplyResponse sendMessage(UUID userId, UUID conversationId, ChatMessageRequest request) {
        Conversation conversation = findOwnedOrThrow(userId, conversationId);

        Message userMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MessageRole.USER)
                .content(request.message())
                .build());

        PendingOperation pendingOperation = loadPendingOperation(conversation);
        ConversationContext context = buildContext(conversationId);
        FinancialStateSnapshot snapshot = buildFinancialSnapshot(userId);

        AIServiceRequest firstCall = new AIServiceRequest(
                UUID.randomUUID().toString(),
                "1",
                conversationId.toString(),
                request.message(),
                context,
                pendingOperation,
                null,
                snapshot,
                null);

        ProcessingResult result = aiClient.process(firstCall);
        Outcome outcome = resolveOutcome(userId, conversationId, request.message(), context, result);

        savePendingOperation(conversation, outcome.pendingOperation());
        conversationRepository.save(conversation);

        Message assistantMessage = messageRepository.save(Message.builder()
                .conversation(conversation)
                .role(MessageRole.ASSISTANT)
                .content(outcome.replyText())
                .build());

        return new ChatReplyResponse(
                conversationId,
                toResponse(userMessage),
                toResponse(assistantMessage),
                outcome.actionExecuted());
    }

    /**
     * Interpreta a decisão da IA. Para ACTION_REQUEST, executa a operação
     * de verdade (via os services existentes) e faz a segunda chamada com
     * a confirmação de execução, para a IA compor a resposta final.
     */
    private Outcome resolveOutcome(UUID userId, UUID conversationId, String originalMessage,
                                    ConversationContext context, ProcessingResult result) {

        switch (result.decision().type()) {
            case ACTION_REQUEST -> {
                ActionRequest action = result.action_request();
                ExecutionConfirmation confirmation = executeAction(userId, action);

                AIServiceRequest confirmCall = new AIServiceRequest(
                        UUID.randomUUID().toString(),
                        "1",
                        conversationId.toString(),
                        originalMessage,
                        context,
                        null,
                        action,
                        null,
                        confirmation);

                ProcessingResult finalResult = aiClient.process(confirmCall);
                String reply = finalResult.response() != null
                        ? finalResult.response().message()
                        : "Não consegui confirmar a operação.";

                return new Outcome(reply, null, confirmation.status() == ExecutionStatus.SUCCESSFUL);
            }
            case CLARIFICATION, CONFIRMATION -> {
                String reply = result.response() != null
                        ? result.response().message()
                        : "Preciso de mais informações para continuar.";
                return new Outcome(reply, result.pending_operation(), false);
            }
            default -> {
                String reply = result.response() != null
                        ? result.response().message()
                        : "Não entendi, pode reformular?";
                return new Outcome(reply, null, false);
            }
        }
    }

    /**
     * Executa a ação proposta pela IA usando os services de domínio já
     * existentes. Qualquer falha (categoria/meta não encontrada, valor
     * ausente, operação ainda não suportada) resulta em confirmação
     * negativa — nunca fingimos sucesso.
     */
    private ExecutionConfirmation executeAction(UUID userId, ActionRequest action) {
        ParsedFinancialData payload = action.payload();

        try {
            switch (action.operation()) {
                case EXPENSE, INCOME -> {
                    return executeTransaction(userId, action, payload);
                }
                case GOAL_CONTRIBUTION -> {
                    return executeGoalContribution(userId, action, payload);
                }
                default -> {
                    return new ExecutionConfirmation(
                            action.action_id(), ExecutionStatus.UNAVAILABLE, null, null,
                            "Esta operação ainda não é suportada pelo Backend no MVP.");
                }
            }
        } catch (Exception ex) {
            log.warn("Falha ao executar ação {} ({}): {}", action.action_id(), action.operation(), ex.getMessage());
            return new ExecutionConfirmation(
                    action.action_id(), ExecutionStatus.FAILED, null, null,
                    "Não foi possível concluir a operação: " + ex.getMessage());
        }
    }

    private ExecutionConfirmation executeTransaction(UUID userId, ActionRequest action, ParsedFinancialData payload) {
        if (payload.monetary_value() == null) {
            return new ExecutionConfirmation(
                    action.action_id(), ExecutionStatus.REJECTED, null, null, "Valor não informado.");
        }

        Category category = findCategoryByName(userId, payload.category());
        if (category == null) {
            return new ExecutionConfirmation(
                    action.action_id(), ExecutionStatus.REJECTED, null, null,
                    "Categoria \"" + payload.category() + "\" não encontrada.");
        }

        TransactionType type = action.operation() == Operation.INCOME
                ? TransactionType.INCOME
                : TransactionType.EXPENSE;

        transactionService.create(userId, new TransactionRequest(
                category.getId(), type, payload.description(), payload.monetary_value(),
                resolveDate(payload.resolved_date())));

        return new ExecutionConfirmation(
                action.action_id(), ExecutionStatus.SUCCESSFUL, action.operation(),
                buildFinancialSnapshot(userId), null);
    }

    private ExecutionConfirmation executeGoalContribution(UUID userId, ActionRequest action, ParsedFinancialData payload) {
        if (payload.monetary_value() == null) {
            return new ExecutionConfirmation(
                    action.action_id(), ExecutionStatus.REJECTED, null, null, "Valor não informado.");
        }

        Goal goal = findGoalByName(userId, payload.goal_reference());
        if (goal == null) {
            return new ExecutionConfirmation(
                    action.action_id(), ExecutionStatus.REJECTED, null, null,
                    "Meta \"" + payload.goal_reference() + "\" não encontrada.");
        }

        goalService.addProgress(userId, goal.getId(), payload.monetary_value());

        return new ExecutionConfirmation(
                action.action_id(), ExecutionStatus.SUCCESSFUL, action.operation(),
                buildFinancialSnapshot(userId), null);
    }

    private Category findCategoryByName(UUID userId, String name) {
        if (name == null) {
            return null;
        }
        return categoryRepository.findByUserIdOrderByNameAsc(userId).stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private Goal findGoalByName(UUID userId, String name) {
        if (name == null) {
            return null;
        }
        return goalRepository.findByUserIdOrderByDeadlineAsc(userId).stream()
                .filter(g -> g.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private LocalDate resolveDate(String resolvedDate) {
        if (resolvedDate == null) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(resolvedDate);
        } catch (DateTimeParseException ex) {
            return LocalDate.now();
        }
    }

    /**
     * Monta o "estado financeiro autorizado" que vai junto de toda
     * requisição à IA — é a única fonte de verdade sobre saldo, metas e
     * lançamentos que ela pode citar (ver Safety Rules do contrato).
     */
    private FinancialStateSnapshot buildFinancialSnapshot(UUID userId) {
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
            log.warn("Não foi possível ler o estado pendente da conversa {}: {}", conversation.getId(), ex.getMessage());
            return null;
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
            log.warn("Não foi possível serializar o estado pendente da conversa {}: {}", conversation.getId(), ex.getMessage());
            conversation.setPendingState(null);
        }
    }

    private Conversation findOwnedOrThrow(UUID userId, UUID conversationId) {
        return conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa"));
    }

    private ConversationResponse toResponse(Conversation c) {
        return new ConversationResponse(c.getId(), c.getTitle(), c.getUpdatedAt());
    }

    private MessageResponse toResponse(Message m) {
        return new MessageResponse(m.getId(), m.getRole().name(), m.getContent(), m.getCreatedAt());
    }

    /** Resultado interno de uma rodada de processamento, antes de persistir. */
    private record Outcome(String replyText, PendingOperation pendingOperation, boolean actionExecuted) {}
}
