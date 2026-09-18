package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** POST /auth/google — troca o ID Token do Google pelos tokens da aplicação. */
public record GoogleLoginRequest(
        @NotBlank(message = "O token do Google é obrigatório.")
        String idToken
) {}
