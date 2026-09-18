package br.com.juntaai.integration.ai;

import br.com.juntaai.exception.AiServiceUnavailableException;
import br.com.juntaai.integration.ai.model.AIServiceRequest;
import br.com.juntaai.integration.ai.model.ProcessingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Porta de saída para o serviço de IA (junta-ai-ai). É a ÚNICA classe do
 * backend que conhece o endpoint real e o transporte HTTP. O contrato de
 * dados (request/response) vem do repositório junta-ai-ai —
 * specs/001-ai-service-foundation/contracts/service-boundary.md — e é
 * espelhado pelos records em integration.ai.model.
 *
 * Cada chamada é uma rodada isolada: o serviço de IA não guarda estado
 * entre requisições, então o Backend sempre reenvia o pending_operation
 * e/ou pending_action da rodada anterior (ver ConversationService).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClient {

    private static final String PROCESS_PATH = "/v1/conversations/process";

    private final RestClient aiServiceRestClient;

    @Value("${ai.service.chat-path:" + PROCESS_PATH + "}")
    private String processPath;

    public ProcessingResult process(AIServiceRequest request) {
        try {
            return aiServiceRestClient.post()
                    .uri(processPath)
                    .body(request)
                    .retrieve()
                    .body(ProcessingResult.class);
        } catch (Exception ex) {
            log.warn("Falha ao chamar o serviço de IA ({}): {}", processPath, ex.getMessage());
            throw new AiServiceUnavailableException(
                    "O assistente está indisponível no momento. Tente novamente em instantes.");
        }
    }
}
