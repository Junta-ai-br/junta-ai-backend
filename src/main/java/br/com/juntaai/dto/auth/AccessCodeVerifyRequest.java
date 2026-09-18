package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** POST /auth/access-code/verify — troca o código pelos tokens de acesso. */
public record AccessCodeVerifyRequest(
        @NotBlank(message = "O e-mail é obrigatório.")
        @Email(message = "Informe um e-mail válido.")
        String email,

        @NotBlank(message = "O código é obrigatório.")
        @Pattern(regexp = "\\d{6}", message = "O código deve ter exatamente 6 dígitos.")
        String code
) {}
