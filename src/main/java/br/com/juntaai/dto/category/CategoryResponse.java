package br.com.juntaai.dto.category;

import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String type,
        Instant createdAt
) {}
