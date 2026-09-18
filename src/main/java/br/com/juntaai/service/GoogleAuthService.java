package br.com.juntaai.service;

import br.com.juntaai.entity.Role;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ApiException;
import br.com.juntaai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Login com Google. Valida o ID Token recebido do frontend contra o
 * endpoint público do Google (tokeninfo) — abordagem simples e sem
 * dependência extra, adequada para o MVP. Se o app crescer, migrar para
 * a biblioteca oficial google-auth-library-oauth2-http é recomendado
 * (valida a assinatura localmente, sem round-trip de rede a cada login).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAuthService {

    private final RestClient googleRestClient;
    private final UserRepository userRepository;

    @Value("${google.oauth.client-id:}")
    private String expectedClientId;

    @Transactional
    public User authenticate(String idToken) {
        Map<String, Object> claims = fetchTokenInfo(idToken);

        String audience = String.valueOf(claims.get("aud"));
        if (expectedClientId.isBlank() || !expectedClientId.equals(audience)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Token do Google inválido para esta aplicação.");
        }

        if (!"true".equals(String.valueOf(claims.get("email_verified")))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "E-mail do Google não verificado.");
        }

        String googleId = String.valueOf(claims.get("sub"));
        String email = String.valueOf(claims.get("email"));
        String name = claims.getOrDefault("name", email).toString();

        return userRepository.findByGoogleId(googleId)
                .or(() -> linkExistingAccountByEmail(email, googleId))
                .orElseGet(() -> createUser(name, email, googleId));
    }

    private java.util.Optional<User> linkExistingAccountByEmail(String email, String googleId) {
        return userRepository.findByEmail(email).map(user -> {
            user.setGoogleId(googleId);
            return userRepository.save(user);
        });
    }

    private User createUser(String name, String email, String googleId) {
        User user = User.builder()
                .name(name)
                .email(email)
                .googleId(googleId)
                .role(Role.USER)
                .build();
        return userRepository.save(user);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchTokenInfo(String idToken) {
        try {
            return googleRestClient.get()
                    .uri("/tokeninfo?id_token={token}", idToken)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception ex) {
            log.warn("Falha ao validar ID Token do Google: {}", ex.getMessage());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Não foi possível validar o login com Google.");
        }
    }
}
