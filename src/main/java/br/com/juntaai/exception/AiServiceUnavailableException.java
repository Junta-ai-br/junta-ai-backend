package br.com.juntaai.exception;

import org.springframework.http.HttpStatus;

/** Lançada quando o serviço de IA (junta-ai-ai) está fora do ar ou expira o timeout. */
public class AiServiceUnavailableException extends ApiException {
    public AiServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
