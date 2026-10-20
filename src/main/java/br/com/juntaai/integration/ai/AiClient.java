package br.com.juntaai.integration.ai;

import br.com.juntaai.exception.AiServiceUnavailableException;
import br.com.juntaai.integration.ai.model.AIServiceRequest;
import br.com.juntaai.integration.ai.model.ProcessingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.UnknownContentTypeException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

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

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final RestClient aiServiceRestClient;

    @Value("${ai.service.chat-path:" + PROCESS_PATH + "}")
    private String processPath;

    public ProcessingResult process(AIServiceRequest request) {
        long started = System.nanoTime();
        int[] observedStatus = {0};
        try {
            // Observe only the status; leave transport, converters and error handling unchanged.
            return aiServiceRestClient.mutate()
                    .requestInterceptor((httpRequest, body, execution) -> {
                        var response = execution.execute(httpRequest, body);
                        observedStatus[0] = response.getStatusCode().value();
                        return response;
                    })
                    .build().post()
                    .uri(processPath)
                    .body(request)
                    .retrieve()
                    .body(ProcessingResult.class);
        } catch (Exception ex) {
            int status = ex instanceof RestClientResponseException http
                    ? http.getStatusCode().value() : observedStatus[0];
            log.warn("ai_service_unavailable category={} duration_ms={} http_status={} request_id={}",
                    failureCategory(ex), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                    status == 0 ? "unknown" : Integer.toString(status), safeRequestId(request));
            throw new AiServiceUnavailableException(
                    "O assistente está indisponível no momento. Tente novamente em instantes.");
        }
    }

    private static String safeRequestId(AIServiceRequest request) {
        String id = request == null ? null : request.request_id();
        return id != null && SAFE_REQUEST_ID.matcher(id).matches() ? id : "unavailable";
    }

    private static String failureCategory(Exception failure) {
        if (failure instanceof RestClientResponseException) return "http_error";
        // Bound traversal even for an unexpected cyclic cause chain. Never log exception text.
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) return "timeout";
        }
        cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof HttpMessageNotWritableException) return "request_serialization";
            if (cause instanceof HttpMessageNotReadableException || cause instanceof UnknownContentTypeException)
                return "response_deserialization";
        }
        if (failure instanceof ResourceAccessException) return "transport";
        return "unexpected";
    }

}
