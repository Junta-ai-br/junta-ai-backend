package br.com.juntaai.service;

import br.com.juntaai.dto.transaction.TransactionRequest;
import br.com.juntaai.entity.*;
import br.com.juntaai.exception.BusinessRuleException;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.integration.ai.model.*;
import br.com.juntaai.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ActionExecutionService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ActionExecutionRepository executions;
    private final MessageEntryRepository entries;
    private final CategoryRepository categories;
    private final GoalRepository goals;
    private final TransactionService transactions;
    private final GoalService goalService;
    private final ConversationTurnStore turns;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Validator validator;

    /** Called through a separate Spring proxy. Returning to the caller means commit completed. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Receipt execute(UUID userId, UUID conversationId, UUID messageId, String requestId,
                           ActionRequest action, String expectedPendingState) {
        Conversation conversation = conversations.findLockedByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa"));
        Message message = messages.findById(messageId).orElseThrow(() -> new ResourceNotFoundException("Mensagem"));
        if (!message.getConversation().getId().equals(conversationId) || message.getRole() != MessageRole.USER) {
            throw new BusinessRuleException("action_association_invalid");
        }
        if (action == null || action.action_id() == null || action.action_id().isBlank()
                || action.action_id().length() > 200 || action.operation() == null || action.payload() == null) {
            throw new BusinessRuleException("action_invalid");
        }
        String content = write(action);
        var entry = entries.byMessage(messageId);
        if (entry != null && (!entry.userId().equals(userId) || !entry.conversationId().equals(conversationId)
                || entry.recovering() || !action.action_id().equals(entry.actionId())
                || !entry.requestId().equals(requestId)
                || !java.util.Objects.equals(entry.pendingBefore(), expectedPendingState)
                || !("ACTION_SELECTED".equals(entry.state()) || "RECEIPT_PENDING".equals(entry.state()) || "COMPLETED".equals(entry.state()))
                || ("ACTION_SELECTED".equals(entry.state()) && !content.equals(entry.actionJson())))) {
            throw new BusinessRuleException("action_entry_mismatch");
        }
        // PostgreSQL arbitrates races, including across different conversations/users.
        jdbc.update("""
                INSERT INTO action_executions(action_id,user_id,conversation_id,user_message_id,request_id,
                                              action_json,pending_state_before)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (action_id) DO NOTHING
                """, action.action_id(), userId, conversationId, messageId, requestId,
                content, conversation.getPendingState());
        ActionExecution execution = executions.findByActionId(action.action_id()).orElseThrow();
        if (!execution.getUserId().equals(userId) || !execution.getConversationId().equals(conversationId)
                || !execution.getUserMessageId().equals(messageId)
                || !execution.getActionJson().equals(content)) {
            throw new BusinessRuleException("action_conflict");
        }
        if (execution.getReceiptJson() != null) {
            if (entry != null) entries.bindReceipt(entry, execution.getActionId());
            return receipt(execution);
        }
        if (entry != null && !"ACTION_SELECTED".equals(entry.state())) {
            throw new BusinessRuleException("action_entry_mismatch");
        }
        if (!java.util.Objects.equals(conversation.getPendingState(), expectedPendingState)
                || executions.existsByUserIdAndConversationIdAndCompositionPendingTrueAndActionIdNot(
                        userId, conversationId, action.action_id())) {
            throw new BusinessRuleException("action_stage_changed");
        }

        Effect effect;
        if (action.expires_at() != null && !Instant.now().isBefore(action.expires_at())) {
            effect = rejected(action, "action_expired");
        } else if (action.operation() == Operation.CORRECTION
                && !confirmedCorrection(message.getContent(), expectedPendingState, action.payload())) {
            effect = rejected(action, "correction_confirmation_required");
        } else if ((action.operation() == Operation.GOAL_CONTRIBUTION || action.operation() == Operation.GOAL_WITHDRAWAL)
                && !confirmedGoalMovement(userId, message.getContent(), expectedPendingState, action.payload(), action.operation())) {
            effect = rejected(action, action.operation() == Operation.GOAL_WITHDRAWAL
                    ? "goal_withdrawal_confirmation_required" : "goal_contribution_confirmation_required");
        } else {
            effect = executeEffect(userId, action);
        }
        execution.setStatus(effect.confirmation().status());
        execution.setEffectId(effect.id());
        execution.setReceiptJson(write(effect.confirmation()));
        executions.saveAndFlush(execution);
        if (entry != null) entries.bindReceipt(entry, execution.getActionId());
        return receipt(execution); // provisional internally; caller receives it only after proxy commit.
    }

    @Transactional(readOnly = true)
    public Receipt pendingReceipt(UUID userId, UUID conversationId) {
        conversations.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa"));
        return executions.findFirstByUserIdAndConversationIdAndCompositionPendingTrueOrderByCreatedAtAsc(
                userId, conversationId).map(this::receipt).orElse(null);
    }

    @Transactional
    public Receipt recordedReceipt(UUID userId, UUID conversationId, String actionId) {
        conversations.findLockedByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversa"));
        var execution = executions.findByActionId(actionId).orElseThrow();
        if (!execution.getUserId().equals(userId) || !execution.getConversationId().equals(conversationId)) {
            throw new BusinessRuleException("action_association_invalid");
        }
        return receipt(execution);
    }

    private Effect executeEffect(UUID userId, ActionRequest action) {
        ParsedFinancialData p = action.payload();
        if (action.operation() == Operation.CORRECTION) return correct(userId, action);
        if (p.correction_target() != null) return rejected(action, "financial_data_invalid");
        if (action.operation() != Operation.EXPENSE && action.operation() != Operation.INCOME
                && action.operation() != Operation.GOAL_CONTRIBUTION && action.operation() != Operation.GOAL_WITHDRAWAL) {
            return new Effect(new ExecutionConfirmation(action.action_id(), ExecutionStatus.UNAVAILABLE,
                    null, null, "operation_unavailable"), null);
        }
        if (p.monetary_value() == null || p.monetary_value().signum() <= 0
                || p.monetary_value().stripTrailingZeros().scale() > 2
                || p.monetary_value().precision() - p.monetary_value().scale() > 17
                || (p.transaction_type() != null && p.transaction_type() != action.operation())) {
            return rejected(action, "financial_data_invalid");
        }
        UUID effectId;
        if (action.operation() == Operation.GOAL_CONTRIBUTION || action.operation() == Operation.GOAL_WITHDRAWAL) {
            var matches = goals.findLockedByUserIdAndName(userId, p.goal_reference());
            if (matches.size() != 1 || p.category() != null) return rejected(action, "goal_unresolved");
            Goal goal = matches.getFirst();
            entityManager.refresh(goal);
            if (action.operation() == Operation.GOAL_WITHDRAWAL) {
                if (goal.getStatus() == GoalStatus.CANCELLED) return rejected(action, "goal_ineligible");
                if (p.monetary_value().compareTo(goal.getCurrentAmount()) > 0) return rejected(action, "goal_insufficient_progress");
                if (p.monetary_value().compareTo(new java.math.BigDecimal("999999999999999.99")) > 0) return rejected(action, "financial_data_invalid");
                goalService.withdrawProgress(userId, goal.getId(), p.monetary_value());
            } else {
                if (goal.getStatus() != GoalStatus.IN_PROGRESS) return rejected(action, "goal_ineligible");
                goalService.addProgress(userId, goal.getId(), p.monetary_value());
            }
            effectId = goal.getId();
        } else {
            var matches = categories.findByUserIdOrderByNameAsc(userId).stream()
                    .filter(c -> c.getName().equalsIgnoreCase(p.category())).toList();
            if (matches.size() != 1) return rejected(action, "category_unresolved");
            Category category = matches.getFirst();
            TransactionType type = action.operation() == Operation.INCOME ? TransactionType.INCOME : TransactionType.EXPENSE;
            if (category.getType() != CategoryType.BOTH && !category.getType().name().equals(type.name())) {
                return rejected(action, "category_ineligible");
            }
            LocalDate date;
            try { date = p.resolved_date() == null ? LocalDate.now() : LocalDate.parse(p.resolved_date()); }
            catch (RuntimeException ignored) { return rejected(action, "date_invalid"); }
            TransactionRequest input = new TransactionRequest(category.getId(), type, p.description(), p.monetary_value(), date);
            if (!validator.validate(input).isEmpty()) return rejected(action, "financial_data_invalid");
            effectId = transactions.create(userId, input).id();
        }
        return new Effect(new ExecutionConfirmation(action.action_id(), ExecutionStatus.SUCCESSFUL,
                action.operation(), turns.buildFinancialSnapshot(userId), null), effectId);
    }

    private Effect rejected(ActionRequest action, String reason) {
        return new Effect(new ExecutionConfirmation(action.action_id(), ExecutionStatus.REJECTED,
                null, null, reason), null);
    }

    private boolean confirmedGoalMovement(UUID userId, String reply, String pendingJson, ParsedFinancialData payload, Operation operation) {
        if (pendingJson == null || payload.temporal_status() != TemporalStatus.REALIZED
                || !java.util.Set.of("sim", "confirmo", "pode confirmar", "sim, pode confirmar")
                    .contains(reply.replaceAll("[\\s\\u00A0]+", " ").replaceAll("^ +| +$", "")
                        .toLowerCase(java.util.Locale.ROOT).replaceAll("[.!]+$", "").replaceAll(" +$", ""))) return false;
        PendingOperation pending;
        UUID target;
        try {
            pending = mapper.readValue(pendingJson, PendingOperation.class);
            if (pending == null || pending.operation() != operation
                    || pending.state() != PendingState.GOAL
                    || !java.util.List.of("confirmation").equals(pending.unresolved_fields())
                    || pending.target_reference() == null
                    || !mapper.valueToTree(pending.known_fields()).equals(mapper.valueToTree(payload))) return false;
            target = UUID.fromString(pending.target_reference());
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
            return false;
        }
        // Resolve only this authenticated user's exact, unique name under lock.
        // Query failures propagate: they are not reclassified as missing consent.
        var matches = goals.findLockedByUserIdAndName(userId, payload.goal_reference());
        return matches.size() == 1 && target.equals(matches.getFirst().getId());
    }

    private boolean confirmedCorrection(String reply, String pendingJson, ParsedFinancialData payload) {
        if (!java.util.Set.of("sim", "confirmo", "pode confirmar", "sim, pode confirmar")
                .contains(reply.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("[.!]+$", ""))) return false;
        try {
            var pending = mapper.readValue(pendingJson, PendingOperation.class);
            return pending.operation() == Operation.CORRECTION
                    && pending.state() == PendingState.CORRECTION
                    && pending.unresolved_fields().equals(java.util.List.of("confirmation"))
                    && mapper.valueToTree(pending.known_fields()).equals(mapper.valueToTree(payload));
        } catch (Exception ignored) { return false; }
    }

    private Effect correct(UUID userId, ActionRequest action) {
        var p = action.payload();
        if (p.correction_target() == null || p.correction_target().id() == null
                || p.correction_target().updated_at() == null || p.goal_reference() != null
                || (p.transaction_type() != Operation.INCOME && p.transaction_type() != Operation.EXPENSE)
                || p.monetary_value() == null || p.monetary_value().signum() <= 0
                || p.monetary_value().stripTrailingZeros().scale() > 2
                || p.monetary_value().compareTo(new java.math.BigDecimal("999999999999999.99")) > 0) {
            return rejected(action, "financial_data_invalid");
        }
        var matches = categories.findByUserIdOrderByNameAsc(userId).stream()
                .filter(c -> c.getName().equalsIgnoreCase(p.category())).toList();
        if (matches.size() != 1) return rejected(action, "category_unresolved");
        var category = matches.getFirst();
        TransactionType type = p.transaction_type() == Operation.INCOME ? TransactionType.INCOME : TransactionType.EXPENSE;
        if (category.getType() != CategoryType.BOTH && !category.getType().name().equals(type.name())) {
            return rejected(action, "category_ineligible");
        }
        var updated = transactions.correct(userId, p.correction_target().id(), p.correction_target().updated_at(),
                type, category.getId(), p.monetary_value());
        if (updated == null) return rejected(action, "transaction_missing_or_changed");
        entityManager.flush();
        return new Effect(new ExecutionConfirmation(action.action_id(), ExecutionStatus.SUCCESSFUL,
                Operation.CORRECTION, turns.buildFinancialSnapshot(userId), null), updated.id());
    }

    private Receipt receipt(ActionExecution e) {
        try {
            Message original = messages.findById(e.getUserMessageId()).orElseThrow();
            ActionRequest action = mapper.readValue(e.getActionJson(), ActionRequest.class);
            ExecutionConfirmation confirmation = mapper.readerFor(ExecutionConfirmation.class)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(e.getReceiptJson());
            if (!e.getActionId().equals(action.action_id()) || !e.getActionId().equals(confirmation.action_id())
                    || e.getStatus() != confirmation.status()
                    || (confirmation.status() == ExecutionStatus.SUCCESSFUL
                        && (confirmation.executed_operation() != action.operation()
                            || confirmation.authoritative_state() == null || e.getEffectId() == null))) {
                throw new BusinessRuleException("execution_evidence_invalid");
            }
            return new Receipt(e.getActionId(), action, confirmation, original.getContent(),
                    e.getPendingStateBefore(), e.getRequestId());
        } catch (Exception ignored) { throw new BusinessRuleException("execution_evidence_invalid"); }
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ignored) { throw new BusinessRuleException("execution_evidence_invalid"); }
    }

    private record Effect(ExecutionConfirmation confirmation, UUID id) {}
    public record Receipt(String actionId, ActionRequest action, ExecutionConfirmation confirmation,
                          String originalMessage, String pendingStateBefore, String originalRequestId) {}
}
