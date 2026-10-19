package br.com.juntaai.dto.goal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record GoalProgressRequest(
        @NotNull(message = "O valor é obrigatório.")
        @DecimalMin(value = "0.01", message = "O valor deve ser maior que zero.")
        @Digits(integer = 15, fraction = 2, message = "Valor fora do limite permitido (até 15 dígitos e 2 casas decimais).")
        BigDecimal amount
) {}
