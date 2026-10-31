package br.com.juntaai.service;

import br.com.juntaai.AbstractIntegrationTest;
import br.com.juntaai.dto.goal.GoalRequest;
import br.com.juntaai.exception.BusinessRuleException;
import br.com.juntaai.exception.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "I3_DISPOSABLE_DB", matches = "true")
class GuardadoPostgresTest extends AbstractIntegrationTest {
    @Autowired GoalService goals;
    @Autowired DashboardService dashboard;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    UUID user;
    @BeforeEach void seed() throws Exception {
        try (var connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).contains("junta_i3_disposable_");
        }
        user = newUser();
    }
    UUID newUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id,name,email) values(?,?,?)", id, "Synthetic", id + "@test.invalid");
        return id;
    }
    UUID goal(UUID owner, String target) {
        return goals.create(owner, new GoalRequest("Synthetic", new BigDecimal(target), null)).id();
    }
    @Test void exactOwnedSumIncludesCompletedBalancesAndIgnoresPeriod() {
        var a = goal(user, "0.10");
        var b = goal(user, "999999999999999.99");
        goals.addProgress(user, a, new BigDecimal("0.10"));
        goals.addProgress(user, b, new BigDecimal("999999999999999.98"));
        var foreign = newUser();
        goals.addProgress(foreign, goal(foreign, "100"), new BigDecimal("77.77"));
        for (var date : new LocalDate[]{LocalDate.of(2020, 1, 1), LocalDate.now()}) {
            var result = dashboard.summarize(user, date, date);
            assertThat(result.saved()).isEqualByComparingTo("1000000000000000.08");
            assertThat(result.balance()).isEqualByComparingTo("0");
            assertThat(result.totalIncome()).isEqualByComparingTo("0");
            assertThat(result.totalExpense()).isEqualByComparingTo("0");
        }
        goals.withdrawProgress(user, a, new BigDecimal("0.10"));
        goals.delete(user, a);
        assertThat(dashboard.summarize(newUser(), LocalDate.now(), LocalDate.now()).saved()).isEqualByComparingTo("0");
    }
    @Test void fundedGoalCannotBeDeletedAndOwnershipIsEnforced() {
        UUID id = goal(user, "10");
        goals.addProgress(user, id, new BigDecimal("10"));
        assertThatThrownBy(() -> goals.delete(user, id)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> goals.delete(newUser(), id)).isInstanceOf(ResourceNotFoundException.class);
        goals.withdrawProgress(user, id, new BigDecimal("10"));
        goals.delete(user, id);
    }
    @Test void contributionLockPreventsConcurrentDeletionOfItsBalance() throws Exception {
        UUID id = goal(user, "100");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var contribution = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                goals.addProgress(user, id, new BigDecimal("0.01"));
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var deletionStarted = new CountDownLatch(1);
            var deletion = pool.submit(() -> { deletionStarted.countDown(); goals.delete(user, id); });
            assertThat(deletionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> deletion.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            contribution.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> deletion.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessRuleException.class);
            assertThat(dashboard.summarize(user, LocalDate.now(), LocalDate.now()).saved()).isEqualByComparingTo("0.01");
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void deletionLockPreventsContributionToDeletedGoal() throws Exception {
        UUID id = goal(user, "100");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var deletion = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                goals.delete(user, id);
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var contribution = pool.submit(() -> goals.addProgress(user, id, new BigDecimal("0.01")));
            assertThatThrownBy(() -> contribution.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); deletion.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> contribution.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ResourceNotFoundException.class);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
