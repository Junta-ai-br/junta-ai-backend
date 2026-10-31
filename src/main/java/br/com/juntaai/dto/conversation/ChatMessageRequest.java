package br.com.juntaai.dto.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatMessageRequest(
        @NotBlank(message = "A mensagem não pode estar vazia.")
        @Size(max = 2000, message = "A mensagem deve ter no máximo 2000 caracteres.")
        String message,
        @jakarta.validation.Valid br.com.juntaai.integration.ai.model.QuickReplySelection quickReply
) {
    public ChatMessageRequest(String message) { this(message, null); }
    public String effectiveMessage() { return quickReply == null ? message : quickReply.label(); }
}
