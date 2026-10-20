package br.com.juntaai.dto.dashboard;

import java.time.LocalDate;

/** Educational monthly indicator. Null score/classification means insufficient data, never 50. */
public record HealthScoreResponse(
        String methodology,
        String status,
        Integer score,
        String classification,
        LocalDate from,
        LocalDate to
) {}
