package br.com.juntaai.integration.ai.model;

import java.util.List;
import java.util.Map;

/**
 * Espelha junta_ai.domain.models.FinancialStateSnapshot. É a "verdade
 * autorizada" que o Backend fornece à IA — a IA nunca inventa saldo,
 * metas ou lançamentos que não vieram daqui.
 */
public record FinancialStateSnapshot(
        String version,
        Map<String, Object> current_state,
        Map<String, Object> projected_state,
        List<Map<String, Object>> realized_records,
        List<Map<String, Object>> planned_records,
        Map<String, Object> goal_progress,
        Map<String, Object> indicators
) {}
