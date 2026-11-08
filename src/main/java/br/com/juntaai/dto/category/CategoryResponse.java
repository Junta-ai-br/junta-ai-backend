package br.com.juntaai.dto.category;

import br.com.juntaai.entity.StandardCategoryKey;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name,
        String type,
        Instant createdAt,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, nullable = true,
                description = "Immutable presentation key; null for unclassified or custom categories")
        StandardCategoryKey standardKey
) {}
