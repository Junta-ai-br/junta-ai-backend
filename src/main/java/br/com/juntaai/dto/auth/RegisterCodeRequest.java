package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /auth/register/request-code — primeiro passo do cadastro: só o e-mail. */
public record RegisterCodeRequest(
        @NotBlank(message = "O e-mail é obrigatório.")
        @Email(message = "Informe um e-mail válido.")
        @Size(max = 180, message = "O e-mail deve ter no máximo 180 caracteres.")
        String email
) {
    public RegisterCodeRequest {
        email = EmailNormalizer.normalize(email);
    }
}