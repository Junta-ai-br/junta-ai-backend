package br.com.juntaai.service;

import br.com.juntaai.entity.*;
import br.com.juntaai.exception.*;
import br.com.juntaai.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalWithdrawalTest {
    GoalRepository repository; GoalService service; UUID user=UUID.randomUUID(), id=UUID.randomUUID();
    @BeforeEach void setup(){ repository=mock(GoalRepository.class); service=new GoalService(repository,mock(UserRepository.class)); }
    Goal goal(String current, GoalStatus status){
        var g=Goal.builder().name("Viagem").targetAmount(new BigDecimal("100.00")).currentAmount(new BigDecimal(current)).status(status).build();
        g.setId(id); when(repository.findLockedByIdAndUserId(id,user)).thenReturn(Optional.of(g)); return g;
    }
    @Test void positiveWithdrawalReducesProgressAndNeverCreatesFinancialTransactions(){
        goal("80.50",GoalStatus.IN_PROGRESS);
        assertThat(service.withdrawProgress(user,id,new BigDecimal("30.25")).currentAmount()).isEqualByComparingTo("50.25");
    }
    @Test void completedGoalReopensOnlyWhenBelowTarget(){
        goal("150.00",GoalStatus.COMPLETED);
        assertThat(service.withdrawProgress(user,id,new BigDecimal("20.00")).status()).isEqualTo("COMPLETED");
        assertThat(service.withdrawProgress(user,id,new BigDecimal("40.00")).status()).isEqualTo("IN_PROGRESS");
    }
    @Test void allAvailableProgressCanBeWithdrawn(){
        goal("100.00",GoalStatus.COMPLETED);
        var result=service.withdrawProgress(user,id,new BigDecimal("100.00"));
        assertThat(result.currentAmount()).isEqualByComparingTo("0"); assertThat(result.status()).isEqualTo("IN_PROGRESS");
    }
    @Test void insufficientProgressAndCancelledGoalRemainUnchanged(){
        var g=goal("20.00",GoalStatus.IN_PROGRESS);
        assertThatThrownBy(()->service.withdrawProgress(user,id,new BigDecimal("20.01"))).isInstanceOf(BusinessRuleException.class);
        assertThat(g.getCurrentAmount()).isEqualByComparingTo("20.00");
        g.setStatus(GoalStatus.CANCELLED);
        assertThatThrownBy(()->service.withdrawProgress(user,id,BigDecimal.ONE)).isInstanceOf(BusinessRuleException.class);
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"0","-1","0.001","1000000000000000"})
    void invalidAmountsNeverReachTheGoal(String amount){
        assertThatThrownBy(()->service.withdrawProgress(user,id,amount==null?null:new BigDecimal(amount))).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(repository);
    }
    @Test void missingOrForeignGoalIsNotAccessible(){
        when(repository.findLockedByIdAndUserId(id,user)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.withdrawProgress(user,id,BigDecimal.ONE)).isInstanceOf(ResourceNotFoundException.class);
    }
}
