package br.com.juntaai.dto.planner;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SimulationResponse(UUID id, String goalName, BigDecimal goalAmount,
        Integer deadlineMonths, String analysisBase, BigDecimal monthlyAmount,
        Integer estimatedMonths, SimulationRequest.HistoryContext historyContext, Instant createdAt) {}
