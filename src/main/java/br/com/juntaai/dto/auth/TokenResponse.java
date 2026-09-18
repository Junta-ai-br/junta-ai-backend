package br.com.juntaai.dto.auth;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds
) {}
