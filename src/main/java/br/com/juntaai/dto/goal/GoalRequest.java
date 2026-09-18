package br.com.juntaai.dto.goal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GoalRequest(

        @NotBlank(message = "O nome da meta é obrigatório.")
        @Size(min = 2, max = 150, message = "O nome deve ter entre 2 e 150 caracteres.")
        String name,

        @NotNull(message = "O valor alvo é obrigatório.")
        @DecimalMin(value = "0.01", message = "O valor alvo deve ser maior que zero.")
        BigDecimal targetAmount,

        @FutureOrPresent(message = "O prazo não pode ser no passado.")
        LocalDate deadline
) {}
