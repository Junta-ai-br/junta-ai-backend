package br.com.juntaai.service;

import br.com.juntaai.entity.TransactionType;
import br.com.juntaai.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    private DashboardService dashboardService;

    private final UUID userId = UUID.randomUUID();
    private final LocalDate from = LocalDate.now().minusMonths(1);
    private final LocalDate to = LocalDate.now();

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(transactionRepository);
    }

    @Test
    void deveCalcularSaldoComoReceitaMenosDespesa() {
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.INCOME, from, to))
                .thenReturn(new BigDecimal("3000.00"));
        when(transactionRepository.sumByUserAndTypeAndPeriod(userId, TransactionType.EXPENSE, from, to))
                .thenReturn(new BigDecimal("1200.50"));

        var response = dashboardService.summarize(userId, from, to);

        assertThat(response.totalIncome()).isEqualByComparingTo("3000.00");
        assertThat(response.totalExpense()).isEqualByComparingTo("1200.50");
        assertThat(response.balance()).isEqualByComparingTo("1799.50");
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
}
