package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /auth/access-code/request — dispara o envio do código de 6 dígitos. */
public record AccessCodeRequest(
        @NotBlank(message = "O e-mail é obrigatório.")
        @Email(message = "Informe um e-mail válido.")
        @Size(max = 180, message = "O e-mail deve ter no máximo 180 caracteres.")
        String email
) {
    public AccessCodeRequest {
        email = EmailNormalizer.normalize(email);
    }
}