package br.com.juntaai.service;

import br.com.juntaai.entity.TransactionType;
import br.com.juntaai.repository.TransactionRepository;
import br.com.juntaai.repository.GoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock private GoalRepository goalRepository;

    private DashboardService dashboardService;

    private final UUID userId = UUID.randomUUID();
    private final LocalDate from = LocalDate.now().minusMonths(1);
    private final LocalDate to = LocalDate.now();

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(transactionRepository, goalRepository);
    }

    @Test
    void deveCalcularSaldoComoReceitaMenosDespesa() {
        when(goalRepository.sumCurrentAmountByUserId(userId)).thenReturn(new BigDecimal("9999.99"));
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.INCOME, from, to))
                .thenReturn(new BigDecimal("3000.00"));
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.EXPENSE, from, to))
                .thenReturn(new BigDecimal("1200.50"));

        var response = dashboardService.summarize(userId, from, to);

        assertThat(response.totalIncome()).isEqualByComparingTo("3000.00");
        assertThat(response.totalExpense()).isEqualByComparingTo("1200.50");
        assertThat(response.balance()).isEqualByComparingTo("1799.50");
        assertThat(response.saved()).isEqualByComparingTo("9999.99");
        org.mockito.Mockito.verify(goalRepository).sumCurrentAmountByUserId(userId);
    }

    @Test
    void deveRetornarSaldoNegativoQuandoDespesaSuperaReceita() {
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.INCOME, from, to))
                .thenReturn(new BigDecimal("500.00"));
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.EXPENSE, from, to))
                .thenReturn(new BigDecimal("800.00"));

        var response = dashboardService.summarize(userId, from, to);

        assertThat(response.balance()).isEqualByComparingTo("-300.00");
    }

    @Test
    void deveRetornarZeradoQuandoNaoHaTransacoesNoPeriodo() {
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.INCOME, from, to))
                .thenReturn(BigDecimal.ZERO);
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.EXPENSE, from, to))
                .thenReturn(BigDecimal.ZERO);

        var response = dashboardService.summarize(userId, from, to);

        assertThat(response.balance()).isEqualByComparingTo("0");
        assertThat(response.categoryBreakdown()).isEmpty();
    }
    @ParameterizedTest
    @CsvSource({"1000,1100,0,Vamos cuidar disso", "1000,950,31,Hora de ajustar",
            "1000,850,54,Em evolução", "1000,500,65,No caminho certo",
            "1000,100,93,Excelente! Tudo tranquilo por aqui!", "1000,0.01,100,Excelente! Tudo tranquilo por aqui!"})
    void officialMonthlyScoreUsesApprovedWeightsAndBands(String income, String expense, int expected, String label) {
        LocalDate first = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.INCOME, first, last)).thenReturn(new BigDecimal(income));
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.EXPENSE, first, last)).thenReturn(new BigDecimal(expense));
        var summary = dashboardService.summarize(userId, first, last);
        var health = summary.healthScore();
        assertThat(health.score()).isEqualTo(expected).isBetween(0, 100);
        assertThat(health.classification()).isEqualTo(label);
        assertThat(health.status()).isEqualTo("CALCULATED");
        assertThat(health.from()).isEqualTo(first);
        assertThat(health.to()).isEqualTo(last);
        // Categories are intentionally absent from inputs: personalized names cannot change the score.
        org.mockito.Mockito.verify(transactionRepository).sumByUserAndTypeAndPeriod(userId, TransactionType.INCOME, first, last);
        org.mockito.Mockito.verify(transactionRepository).sumByUserAndTypeAndPeriod(userId, TransactionType.EXPENSE, first, last);
        org.mockito.Mockito.verifyNoMoreInteractions(transactionRepository);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,100", "100,0"})
    void incompleteMonthlyEvidenceHasNoNumericScore(String income, String expense) {
        LocalDate first = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.INCOME, first, last)).thenReturn(new BigDecimal(income));
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.EXPENSE, first, last)).thenReturn(new BigDecimal(expense));
        var health = dashboardService.summarize(userId, first, last).healthScore();
        assertThat(health.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(health.score()).isNull();
        assertThat(health.classification()).isNull();
    }

}
