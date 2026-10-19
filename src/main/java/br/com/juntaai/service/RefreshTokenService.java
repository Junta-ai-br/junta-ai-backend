package br.com.juntaai.service;

import br.com.juntaai.entity.RefreshToken;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ApiException;
import br.com.juntaai.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Refresh tokens opacos (não são JWT), com rotação a cada uso.
 *
 * O valor entregue ao usuário tem 256 bits aleatórios e NUNCA é gravado:
 * o banco guarda só o hash SHA-256. Assim, se o banco vazar, os hashes não
 * servem para entrar em nenhuma conta. (SHA-256 basta aqui porque o token já
 * é aleatório e longo; BCrypt só faria sentido para segredos curtos.)
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    /** Cria o token e devolve o valor em texto puro, que só existe neste momento. */
    @Transactional
    public String create(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(hash(raw))
                .expiryDate(Instant.now().plusMillis(refreshTokenExpirationMs))
                .revoked(false)
                .build());
        return raw;
    }

    /**
     * Valida e gasta o refresh token (rotação: cada um só vale uma vez).
     *
     * Se alguém apresentar um token JÁ usado, tratamos como possível roubo
     * (alguém guardou uma cópia) e encerramos todas as sessões do usuário.
     * noRollbackFor: sem isso, o rollback desfaria essa revogação.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public User validateAndRotate(String tokenValue) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(hash(tokenValue))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token inválido."));

        if (refreshToken.isRevoked()) {
            refreshTokenRepository.deleteAllByUserId(refreshToken.getUser().getId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token inválido ou expirado.");
        }
        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token inválido ou expirado.");
        }
        if (refreshTokenRepository.revokeIfActive(refreshToken.getId()) == 0) {
            // Outra requisição gastou este token no mesmo instante.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token inválido ou expirado.");
        }

        return refreshToken.getUser();
    }

    /** Logout: revoga se existir; token desconhecido não é erro (logout é idempotente). */
    @Transactional
    public void revoke(String tokenValue) {
        refreshTokenRepository.revokeByToken(hash(tokenValue));
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.deleteAllByUserId(userId);
    }

    @Transactional
    public int deleteExpired() {
        return refreshTokenRepository.deleteExpiredBefore(Instant.now());
    }

    static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
}