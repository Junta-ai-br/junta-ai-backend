package br.com.juntaai.dto.transaction;

import br.com.juntaai.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionRequest(

        @NotNull(message = "A categoria é obrigatória.")
        UUID categoryId,

        @NotNull(message = "O tipo da transação é obrigatório.")
        TransactionType type,

        @Size(max = 500, message = "A descrição deve ter no máximo 500 caracteres.")
        String description,

        @NotNull(message = "O valor é obrigatório.")
        @DecimalMin(value = "0.01", message = "O valor deve ser maior que zero.")
        BigDecimal amount,

        @NotNull(message = "A data da transação é obrigatória.")
        @PastOrPresent(message = "A data da transação não pode ser no futuro.")
        LocalDate transactionDate
) {}
