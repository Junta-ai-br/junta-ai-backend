package br.com.juntaai.integration.ai.model;

/**
 * Espelha junta_ai.domain.models.ExecutionConfirmation — o Backend monta
 * isso DEPOIS de executar (ou tentar executar) a ação, e devolve pra IA
 * numa segunda chamada para compor a resposta final ao usuário.
 */
public record ExecutionConfirmation(
        String action_id,
        ExecutionStatus status,
        Operation executed_operation,
        FinancialStateSnapshot authoritative_state,
        String reason
) {}
