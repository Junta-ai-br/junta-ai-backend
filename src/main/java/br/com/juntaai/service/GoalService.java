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
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoalService {

    private final GoalRepository goalRepository;
    private final UserRepository userRepository;

    @Transactional
    public GoalResponse create(UUID userId, GoalRequest request) {
        User user = userRepository.getReferenceById(userId);

        Goal goal = Goal.builder()
                .user(user)
                .name(request.name())
                .targetAmount(request.targetAmount())
                .currentAmount(BigDecimal.ZERO)
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
        Goal goal = findOwnedOrThrow(userId, goalId);

        if (goal.getStatus() != GoalStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Metas concluídas ou canceladas não podem ser editadas.");
        }

        goal.setName(request.name());
        goal.setTargetAmount(request.targetAmount());
        goal.setDeadline(request.deadline());
        return toResponse(goal);
    }

    /** Soma um aporte à meta e marca como concluída quando o valor alvo é atingido. */
    @Transactional
    public GoalResponse addProgress(UUID userId, UUID goalId, BigDecimal amount) {
        Goal goal = findOwnedOrThrow(userId, goalId);

        if (goal.getStatus() != GoalStatus.IN_PROGRESS) {
            throw new BusinessRuleException("Esta meta não está mais em andamento.");
        }

        goal.setCurrentAmount(goal.getCurrentAmount().add(amount));

        if (goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0) {
            goal.setStatus(GoalStatus.COMPLETED);
        }

        return toResponse(goal);
    }

    @Transactional
    public void delete(UUID userId, UUID goalId) {
        Goal goal = findOwnedOrThrow(userId, goalId);
        goalRepository.delete(goal);
    }

    private Goal findOwnedOrThrow(UUID userId, UUID goalId) {
        return goalRepository.findByIdAndUserId(goalId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Meta"));
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
