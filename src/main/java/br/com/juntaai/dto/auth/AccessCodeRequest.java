package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** POST /auth/access-code/request — dispara o envio do código de 6 dígitos. */
public record AccessCodeRequest(
        @NotBlank(message = "O e-mail é obrigatório.")
        @Email(message = "Informe um e-mail válido.")
        String email
) {}
