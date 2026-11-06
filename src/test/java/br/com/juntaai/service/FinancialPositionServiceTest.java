package br.com.juntaai.service;
import br.com.juntaai.repository.*;
import br.com.juntaai.entity.*;
import br.com.juntaai.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class FinancialPositionServiceTest {
    final TransactionRepository transactions=mock(TransactionRepository.class);
    final GoalRepository goals=mock(GoalRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final FinancialPositionService positions=new FinancialPositionService(transactions,goals);
    final UUID user=UUID.randomUUID(), id=UUID.randomUUID();
    @Test void reservationPreservesTotalAndIsIndependentOfMonthlyPeriod() {
        when(transactions.accumulatedBalance(eq(user),any())).thenReturn(new BigDecimal("2000.00"));
        when(goals.sumCurrentAmountByUserId(user)).thenReturn(new BigDecimal("150.00"));
        var result=positions.current(user);
        assertThat(result.availableBalance()).isEqualByComparingTo("1850.00");
        assertThat(result.totalTracked()).isEqualByComparingTo("2000.00");
        assertThat(result.reservationInconsistent()).isFalse();
    }
    @Test void legacyInconsistencyIsReportedWithoutChangingData() {
        when(transactions.accumulatedBalance(eq(user),any())).thenReturn(new BigDecimal("100.00"));
        when(goals.sumCurrentAmountByUserId(user)).thenReturn(new BigDecimal("150.00"));
        assertThat(positions.current(user).reservationInconsistent()).isTrue();
        assertThat(positions.current(user).availableBalance()).isEqualByComparingTo("-50.00");
        verify(goals,never()).save(any());
        verify(transactions,never()).save(any());
    }
    @Test void insufficientAvailableCannotChangeGoalAndLockPrecedesRead() {
        var goal=Goal.builder().name("Placa").currentAmount(BigDecimal.ZERO).targetAmount(new BigDecimal("1000")).status(GoalStatus.IN_PROGRESS).build();
        when(goals.findLockedByIdAndUserId(id,user)).thenReturn(Optional.of(goal));
        when(transactions.accumulatedBalance(eq(user),any())).thenReturn(new BigDecimal("100"));
        when(goals.sumCurrentAmountByUserId(user)).thenReturn(BigDecimal.ZERO);
        var service=new GoalService(goals,users,positions);
        assertThatThrownBy(()->service.addProgress(user,id,new BigDecimal("150"))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("goal_insufficient_available");
        assertThat(goal.getCurrentAmount()).isEqualByComparingTo("0");
        var order=inOrder(users,goals,transactions);
        order.verify(users).findLockedById(user);
        order.verify(goals).findLockedByIdAndUserId(id,user);
        order.verify(transactions).accumulatedBalance(eq(user),any());
    }
}
