package br.com.juntaai.dto.auth;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        String role
) {}
