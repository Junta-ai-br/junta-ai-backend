package br.com.juntaai.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefreshRequest(
        @NotBlank(message = "O refresh token é obrigatório.")
        @Size(max = 200, message = "Refresh token inválido.")
        String refreshToken
) {}