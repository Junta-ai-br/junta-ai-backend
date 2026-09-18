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
        List<CategoryBreakdown> categoryBreakdown
) {
    public record CategoryBreakdown(String categoryName, String type, BigDecimal total) {}
}
