package br.com.juntaai.service;

import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.entity.Goal;
import br.com.juntaai.entity.GoalStatus;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.BusinessRuleException;
import br.com.juntaai.repository.GoalRepository;
import br.com.juntaai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoalServiceTest {

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private UserRepository userRepository;

    private GoalService goalService;

    private final UUID userId = UUID.randomUUID();
    private final UUID goalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        goalService = new GoalService(goalRepository, userRepository);
    }

    private Goal buildGoal(BigDecimal target, BigDecimal current, GoalStatus status) {
        Goal goal = Goal.builder()
                .name("Viagem")
                .targetAmount(target)
                .currentAmount(current)
                .status(status)
                .build();
        goal.setId(goalId);
        return goal;
    }

    @Test
    void deveMarcarMetaComoConcluidaQuandoAporteAtingeValorAlvo() {
        Goal goal = buildGoal(new BigDecimal("1000.00"), new BigDecimal("900.00"), GoalStatus.IN_PROGRESS);
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));

        var response = goalService.addProgress(userId, goalId, new BigDecimal("150.00"));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.currentAmount()).isEqualByComparingTo("1050.00");
    }

    @Test
    void naoDeveAceitarAporteEmMetaJaConcluida() {
        Goal goal = buildGoal(new BigDecimal("1000.00"), new BigDecimal("1000.00"), GoalStatus.COMPLETED);
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));

        assertThatThrownBy(() -> goalService.addProgress(userId, goalId, new BigDecimal("50.00")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void naoDevePermitirEditarMetaConcluida() {
        Goal goal = buildGoal(new BigDecimal("1000.00"), new BigDecimal("1000.00"), GoalStatus.COMPLETED);
        when(goalRepository.findByIdAndUserId(goalId, userId)).thenReturn(Optional.of(goal));

        GoalRequest request = new GoalRequest("Viagem 2", new BigDecimal("2000.00"), null);

        assertThatThrownBy(() -> goalService.update(userId, goalId, request))
                .isInstanceOf(BusinessRuleException.class);
    }
}
