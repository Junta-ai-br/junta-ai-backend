package br.com.juntaai.service;

import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.dto.goal.GoalResponse;
import br.com.juntaai.entity.Goal;
import br.com.juntaai.entity.GoalStatus;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.BusinessRuleException;
import br.com.juntaai.exception.ResourceNotFoundException;
import br.com.juntaai.repository.GoalRepository;
import br.com.juntaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository goalRepository;
    private final UserRepository userRepository;
    private final FinancialPositionService positions;

    @Transactional
    public GoalResponse create(UUID userId, GoalRequest request) {
        validateDeadline(request.deadline());
        User user = userRepository.getReferenceById(userId);

        Goal goal = Goal.builder()
                .user(user)
                .name(normalizeName(request.name()))
                .targetAmount(request.targetAmount())
                .currentAmount(BigDecimal.ZERO)
                .confirmedContributionCount(0L)
                .recognizedMilestone(0)
                .deadline(request.deadline())
                .status(GoalStatus.IN_PROGRESS)
                .build();

        return toResponse(goalRepository.save(goal));
    }

    public List<GoalResponse> listForUser(UUID userId) {
        return goalRepository.findByUserIdOrderByDeadlineAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public GoalResponse update(UUID userId, UUID goalId, GoalRequest request) {
        validateDeadline(request.deadline());
        Goal goal = findOwnedOrThrow(userId, goalId);

        if (goal.getStatus() != GoalStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Metas concluídas ou canceladas não podem ser editadas.");
        }

        goal.setName(normalizeName(request.name()));
        goal.setTargetAmount(request.targetAmount());
        goal.setDeadline(request.deadline());
        if (goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0) goal.setStatus(GoalStatus.COMPLETED);
        return toResponse(goal);
    }

    /** Soma um aporte à meta e marca como concluída quando o valor alvo é atingido. */
    @Transactional
    public GoalResponse addProgress(UUID userId, UUID goalId, BigDecimal amount) {
        validateAmount(amount);
        Goal goal = findOwnedOrThrow(userId, goalId);

        if (goal.getStatus() != GoalStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Esta meta não está mais em andamento.");
        }

        String problem = reservationProblem(userId, amount);
        if (problem != null) throw new BusinessRuleException(problem);
        BigDecimal before = goal.getCurrentAmount();
        BigDecimal next = before.add(amount);
        if (next.compareTo(new BigDecimal("99999999999999999.99")) > 0) {
            throw new BusinessRuleException("O valor acumulado excede o limite permitido.");
        }
        goal.setCurrentAmount(next);

        if (goal.getConfirmedContributionCount() != null && goal.getRecognizedMilestone() != null) {
            goal.setConfirmedContributionCount(Math.incrementExact(goal.getConfirmedContributionCount()));
            for (int milestone : new int[]{25, 50, 75, 100}) {
                BigDecimal threshold = goal.getTargetAmount().multiply(BigDecimal.valueOf(milestone));
                if (before.multiply(BigDecimal.valueOf(100)).compareTo(threshold) < 0
                        && next.multiply(BigDecimal.valueOf(100)).compareTo(threshold) >= 0) {
                    goal.setRecognizedMilestone(Math.max(goal.getRecognizedMilestone(), milestone));
                }
            }
        }

        if (goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0) {
            goal.setStatus(GoalStatus.COMPLETED);
        }

        return toResponse(goal);
    }

    @Transactional
    public void delete(UUID userId, UUID goalId) {
        Goal goal = findOwnedOrThrow(userId, goalId);
        if (goal.getCurrentAmount().signum() > 0) {
            throw new BusinessRuleException("Retire o valor guardado antes de excluir a meta.");
        }
        goalRepository.delete(goal);
    }

    /** Reduz somente o acumulado da meta, sem criar movimentação financeira. */
    @Transactional
    public GoalResponse withdrawProgress(UUID userId, UUID goalId, BigDecimal amount) {
        validateAmount(amount);
        Goal goal = findOwnedOrThrow(userId, goalId);
        if (goal.getStatus() == GoalStatus.CANCELLED) {
            throw new BusinessRuleException("Esta meta não aceita retiradas.");
        }
        if (amount.compareTo(goal.getCurrentAmount()) > 0) {
            throw new BusinessRuleException("A retirada não pode superar o valor acumulado na meta.");
        }
        goal.setCurrentAmount(goal.getCurrentAmount().subtract(amount));
        goal.setStatus(goal.getCurrentAmount().compareTo(goal.getTargetAmount()) < 0
                ? GoalStatus.IN_PROGRESS : GoalStatus.COMPLETED);
        return toResponse(goal);
    }

    public String reservationProblem(UUID userId, BigDecimal amount) {
        var position = positions.current(userId);
        if (position.reservationInconsistent()) return "goal_reserve_inconsistent";
        return amount.compareTo(position.availableBalance()) > 0 ? "goal_insufficient_available" : null;
    }

    private Goal findOwnedOrThrow(UUID userId, UUID goalId) {
        userRepository.findLockedById(userId);
        return goalRepository.findLockedByIdAndUserId(goalId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Meta"));
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(new BigDecimal("999999999999999.99")) > 0) {
            throw new BusinessRuleException("Aporte inválido: use valor positivo com até duas casas decimais.");
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.trim().length() < 2 || name.trim().length() > 150) {
            throw new BusinessRuleException("O nome deve ter entre 2 e 150 caracteres.");
        }
        return name.trim();
    }

    private void validateDeadline(LocalDate deadline) {
        if (deadline != null && deadline.isBefore(LocalDate.now())) {
            throw new BusinessRuleException("O prazo não pode ser no passado.");
        }
    }

    private GoalResponse toResponse(Goal goal) {
        return new GoalResponse(
                goal.getId(),
                goal.getName(),
                goal.getTargetAmount(),
                goal.getCurrentAmount(),
                goal.getDeadline(),
                goal.getStatus().name());
    }
}
