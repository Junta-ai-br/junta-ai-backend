package br.com.juntaai.service;

import br.com.juntaai.dto.dashboard.DashboardResponse;
import br.com.juntaai.dto.dashboard.HealthScoreResponse;
import br.com.juntaai.entity.TransactionType;
import br.com.juntaai.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Camada de consulta/DTO sobre os dados existentes — sem entidade própria,
 * como definido no documento de estrutura de repositórios (seção 7).
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TransactionRepository transactionRepository;

    public DashboardResponse summarize(UUID userId, LocalDate from, LocalDate to) {
        BigDecimal totalIncome = transactionRepository.sumByUserAndTypeAndPeriod(
                userId, TransactionType.INCOME, from, to);
        BigDecimal totalExpense = transactionRepository.sumByUserAndTypeAndPeriod(
                userId, TransactionType.EXPENSE, from, to);

        BigDecimal balance = totalIncome.subtract(totalExpense);

        // Breakdown por categoria fica como próximo incremento (P1); aqui devolvemos
        // a estrutura já pronta para o frontend consumir quando a query for adicionada.
        List<DashboardResponse.CategoryBreakdown> breakdown = List.of();

        return new DashboardResponse(from, to, totalIncome, totalExpense, balance, breakdown,
                calculateHealthScore(totalIncome, totalExpense, from, to));
    }

    private HealthScoreResponse calculateHealthScore(BigDecimal income, BigDecimal expenses, LocalDate from, LocalDate to) {
        LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        // Match the canonical frontend's UTC civil month; never score a historical/partial window.
        if (!month.equals(from) || !month.withDayOfMonth(month.lengthOfMonth()).equals(to)) return null;
        if (income.signum() <= 0 || expenses.signum() <= 0) {
            return new HealthScoreResponse("mvp-monthly-v1", "INSUFFICIENT_DATA", null, null, from, to);
        }
        // Same curves and 45/35/20 weights as the original, with approved monthly semantics:
        // savings / 20%; 100 - (all-expenses commitment / 50%)*100; nonnegative balance.
        BigDecimal balance = income.subtract(expenses);
        BigDecimal savings = clamp(balance.divide(income, MathContext.DECIMAL128).multiply(new BigDecimal("500")));
        BigDecimal commitment = clamp(new BigDecimal("100").subtract(
                expenses.divide(income, MathContext.DECIMAL128).multiply(new BigDecimal("200"))));
        BigDecimal equilibrium = balance.signum() >= 0 ? new BigDecimal("100") : BigDecimal.ZERO;
        int score = savings.multiply(new BigDecimal("0.45"))
                .add(commitment.multiply(new BigDecimal("0.35")))
                .add(equilibrium.multiply(new BigDecimal("0.20")))
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
        String classification = score < 25 ? "Vamos cuidar disso"
                : score < 45 ? "Hora de ajustar"
                : score < 60 ? "Em evolução"
                : score < 80 ? "No caminho certo"
                : "Excelente! Tudo tranquilo por aqui!";
        return new HealthScoreResponse("mvp-monthly-v1", "CALCULATED", score, classification, from, to);
    }

    private BigDecimal clamp(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(new BigDecimal("100"));
    }

}
