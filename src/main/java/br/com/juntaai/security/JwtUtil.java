package br.com.juntaai.security;

import br.com.juntaai.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Geração e validação de access tokens JWT. O refresh token NÃO é um JWT:
 * é um valor opaco persistido em `refresh_tokens`, para permitir revogação
 * (logout, rotação) sem depender de blacklist de JWT.
 */
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                    @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(requireStrongSecret(secret));
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

        /**
     * Falha na inicialização (fail-fast) se o segredo estiver ausente, curto
     * ou for um dos valores de exemplo que já circularam em documentos/repositório.
     * Um segredo conhecido permitiria a qualquer pessoa forjar tokens de qualquer usuário.
     */
    private static byte[] requireStrongSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET não definido. Gere um com: openssl rand -base64 48");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        String lower = secret.toLowerCase();
        if (bytes.length < 32 || lower.contains("troque-esta-chave") || lower.contains("change-this-secret")) {
            throw new IllegalStateException(
                    "JWT_SECRET fraco ou de exemplo. Use pelo menos 32 caracteres aleatórios "
                            + "(openssl rand -base64 48).");
        }
        return bytes;
    }

    public String generateAccessToken(UUID userId, String email, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(email)
                .claim("uid", userId.toString())
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpirationMs)))
                .signWith(signingKey)
                .compact();
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationMs / 1000;
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (Exception ex) {
            return false;
        }
    }

        /**
     * Monta o usuário autenticado só com o que está dentro do token (já
     * validado pela assinatura). Evita uma consulta ao banco a cada
     * requisição. Devolve null se o token for inválido, expirado ou
     * não tiver os campos esperados.
     */
    public AuthenticatedUser toAuthenticatedUser(String token) {
        try {
            Claims claims = parseClaims(token);
            if (claims.getExpiration() == null || !claims.getExpiration().after(new Date())) {
                return null;
            }
            UUID id = UUID.fromString(claims.get("uid", String.class));
            Role role = Role.valueOf(claims.get("role", String.class));
            return new AuthenticatedUser(id, claims.getSubject(), role);
        } catch (Exception ex) {
            return null;
        }
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public UUID extractUserId(String token) {
        return UUID.fromString(parseClaims(token).get("uid", String.class));
    }
}
