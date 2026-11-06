package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.integration.ai.AiClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="I3_DISPOSABLE_DB", matches="true")
class ReservationConsistencyPostgresTest extends AbstractIntegrationTest {
    @Autowired GoalCommandService commands;
    @Autowired GoalService goals;
    @Autowired FinancialPositionService positions;
    @Autowired DashboardService dashboard;
    @Autowired JdbcTemplate jdbc;
    @MockBean AiClient ai;
    UUID user, first, second;
    @BeforeEach void seed() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user=UUID.randomUUID(); UUID category=UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)",user,"Synthetic",user+"@test.invalid");
        jdbc.update("insert into categories(id,user_id,name,type) values(?,?,'Salario','INCOME')",category,user);
        jdbc.update("insert into transactions(id,user_id,category_id,type,description,amount,transaction_date) values(?,?,?,'INCOME','Synthetic',2000,current_date)",UUID.randomUUID(),user,category);
        first=commands.create(user,UUID.randomUUID(),new GoalRequest("Placa",new BigDecimal("5000"),null)).id();
        second=commands.create(user,UUID.randomUUID(),new GoalRequest("Viagem",new BigDecimal("5000"),null)).id();
    }
    @Test void repeatedCommandAndWithdrawalPreserveTotalAndMonthlyIndicators() {
        UUID key=UUID.randomUUID();
        commands.progress(user,key,first,new BigDecimal("150"));
        commands.progress(user,key,first,new BigDecimal("150"));
        assertThat(positions.current(user).availableBalance()).isEqualByComparingTo("1850");
        assertThat(positions.current(user).totalTracked()).isEqualByComparingTo("2000");
        var today=java.time.LocalDate.now();
        var summary=dashboard.summarize(user,today.withDayOfMonth(1),today.withDayOfMonth(today.lengthOfMonth()));
        assertThat(summary.balance()).isEqualByComparingTo("2000");
        assertThat(summary.totalIncome()).isEqualByComparingTo("2000");
        assertThat(summary.totalExpense()).isEqualByComparingTo("0");
        goals.withdrawProgress(user,first,new BigDecimal("50"));
        assertThat(positions.current(user).availableBalance()).isEqualByComparingTo("1900");
        assertThat(jdbc.queryForObject("select count(*) from transactions where user_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void concurrentReservationsOnDifferentGoalsCannotSpendTheSameAvailableMoney() throws Exception {
        var executor=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try {
            var a=executor.submit(()->attempt(first,start)); var b=executor.submit(()->attempt(second,start));
            start.countDown();
            assertThat((a.get(15,TimeUnit.SECONDS)?1:0)+(b.get(15,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
            assertThat(positions.current(user).availableBalance()).isEqualByComparingTo("500");
            assertThat(jdbc.queryForObject("select sum(current_amount) from goals where user_id=?",BigDecimal.class,user)).isEqualByComparingTo("1500");
            assertThat(jdbc.queryForObject("select count(*) from goal_command_receipts where user_id=? and response_json is not null",Integer.class,user)).isEqualTo(3);
        } finally { executor.shutdownNow(); }
    }
    boolean attempt(UUID goal,CountDownLatch start) throws Exception {
        start.await();
        try { commands.progress(user,UUID.randomUUID(),goal,new BigDecimal("1500")); return true; }
        catch(br.com.juntaai.exception.BusinessRuleException e) { assertThat(e.getMessage()).contains("goal_insufficient_available"); return false; }
    }
    @Test void legacyReservesAreFlaggedAndNewReservationsFailWithoutRepairingData() {
        jdbc.update("update goals set current_amount=2100 where id=?",first);
        assertThat(positions.current(user).reservationInconsistent()).isTrue();
        assertThatThrownBy(()->commands.progress(user,UUID.randomUUID(),second,BigDecimal.ONE)).hasMessageContaining("goal_reserve_inconsistent");
        assertThat(jdbc.queryForObject("select current_amount from goals where id=?",BigDecimal.class,first)).isEqualByComparingTo("2100");
        goals.withdrawProgress(user,first,new BigDecimal("200"));
        assertThat(positions.current(user).reservationInconsistent()).isFalse();
        assertThat(positions.current(user).availableBalance()).isEqualByComparingTo("100");
    }
}
