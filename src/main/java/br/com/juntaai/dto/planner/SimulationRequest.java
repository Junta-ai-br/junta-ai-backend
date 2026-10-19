package br.com.juntaai.dto.planner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record SimulationRequest(
        @NotBlank @Size(max = 150) String goalName,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal goalAmount,
        @NotNull @Min(1) @Max(1200) Integer deadlineMonths,
        @NotNull @Pattern(regexp = "current_month|last_30_days|last_3_months|no_history") String analysisBase,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal monthlyAmount,
        @NotNull @Min(1) @Max(12000) Integer estimatedMonths,
        @Valid HistoryContext historyContext
) {
    // Snapshot of aggregates supplied by the deterministic planner, never transaction records.
    public record HistoryContext(
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal income,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal fixedExpenses,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 2) BigDecimal totalExpenses,
            @NotNull @Digits(integer = 17, fraction = 2) BigDecimal available,
            @NotNull @DecimalMin("0") @Digits(integer = 17, fraction = 4) BigDecimal commitmentRate
    ) {}
}
