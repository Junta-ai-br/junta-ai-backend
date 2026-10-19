package br.com.juntaai.exception;

import org.springframework.http.HttpStatus;

/** Safe ingress outcome. Contains no key, submitted text or financial payload. */
public class MessageEntryException extends ApiException {
    private final String code;

    public MessageEntryException(HttpStatus status, String code) {
        super(status, switch (code) {
            case "idempotency_key_required" -> "Informe a chave do envio da mensagem.";
            case "idempotency_key_invalid" -> "A chave do envio deve ser um UUID v4 válido.";
            case "idempotency_key_conflict" -> "A chave já está associada a outro envio.";
            case "message_processing_in_progress" -> "O envio ainda está em processamento. Repita com a mesma chave.";
            default -> "Este envio falhou antes de selecionar uma ação e não será reinterpretado.";
        });
        this.code = code;
    }

    public String getCode() { return code; }

    public static MessageEntryException failed() {
        return new MessageEntryException(HttpStatus.SERVICE_UNAVAILABLE, "message_processing_failed");
    }
}
