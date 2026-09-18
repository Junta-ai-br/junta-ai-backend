package br.com.juntaai.service;

import br.com.juntaai.dto.auth.AccessCodeVerifyRequest;
import br.com.juntaai.dto.auth.RegisterRequest;
import br.com.juntaai.dto.auth.TokenResponse;
import br.com.juntaai.entity.OnboardingAnswers;
import br.com.juntaai.entity.Role;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ConflictException;
import br.com.juntaai.repository.OnboardingAnswersRepository;
import br.com.juntaai.repository.UserRepository;
import br.com.juntaai.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final OnboardingAnswersRepository onboardingAnswersRepository;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final EmailAccessCodeService emailAccessCodeService;
    private final GoogleAuthService googleAuthService;

    /**
     * Cria a conta (Etapa 1 + Etapa 2 do cadastro do frontend) e já
     * devolve os tokens — o e-mail é confirmado depois, no primeiro
     * login por código, não no cadastro em si.
     */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Este e-mail já está cadastrado.");
        }

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .whatsapp(request.whatsapp())
                .role(Role.USER)
                .build();

        userRepository.save(user);
        saveOnboardingAnswersIfPresent(user, request);

        return issueTokens(user);
    }

    @Transactional
    public void requestAccessCode(String email) {
        emailAccessCodeService.requestCode(email);
    }

    @Transactional
    public TokenResponse verifyAccessCode(AccessCodeVerifyRequest request) {
        User user = emailAccessCodeService.verifyCode(request.email(), request.code());
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse loginWithGoogle(String idToken) {
        User user = googleAuthService.authenticate(idToken);
        return issueTokens(user);
    }

    @Transactional
    public TokenResponse refresh(String refreshTokenValue) {
        User user = refreshTokenService.validateAndRotate(refreshTokenValue);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        var token = refreshTokenValue == null ? null : refreshTokenValue.trim();
        if (token != null && !token.isEmpty()) {
            refreshTokenService.validateAndRotate(token);
        }
    }

    private void saveOnboardingAnswersIfPresent(User user, RegisterRequest request) {
        boolean answeredAnything = request.question1() != null
                || request.question2() != null
                || request.question3() != null;

        if (!answeredAnything) {
            return;
        }

        onboardingAnswersRepository.save(OnboardingAnswers.builder()
                .user(user)
                .question1(request.question1())
                .question2(request.question2())
                .question3(request.question3())
                .build());
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshToken = refreshTokenService.create(user).getToken();
        return new TokenResponse(accessToken, refreshToken, jwtUtil.getAccessTokenExpirationSeconds());
    }
}
