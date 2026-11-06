package br.com.juntaai.dto.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DashboardResponse(
        LocalDate from,
        LocalDate to,
        BigDecimal totalIncome,
        BigDecimal totalExpense,
        BigDecimal balance,
        List<CategoryBreakdown> categoryBreakdown,
        HealthScoreResponse healthScore,
        BigDecimal saved,
        BigDecimal availableBalance,
        BigDecimal totalTracked,
        Boolean reservationInconsistent
) {
    public DashboardResponse(LocalDate from, LocalDate to, BigDecimal totalIncome, BigDecimal totalExpense,
            BigDecimal balance, List<CategoryBreakdown> categoryBreakdown, HealthScoreResponse healthScore, BigDecimal saved) {
        this(from, to, totalIncome, totalExpense, balance, categoryBreakdown, healthScore, saved, null, null, null);
    }
    public record CategoryBreakdown(String categoryName, String type, BigDecimal total) {}
}
