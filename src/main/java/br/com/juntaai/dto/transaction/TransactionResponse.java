package br.com.juntaai.dto.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID categoryId,
        String categoryName,
        String type,
        String description,
        BigDecimal amount,
        LocalDate transactionDate,
        Instant createdAt
) {}
