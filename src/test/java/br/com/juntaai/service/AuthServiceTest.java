package br.com.juntaai.service;

import br.com.juntaai.dto.auth.AccessCodeVerifyRequest;
import br.com.juntaai.dto.auth.RegisterCodeRequest;
import br.com.juntaai.dto.auth.RegisterVerifyRequest;
import br.com.juntaai.entity.OnboardingAnswers;
import br.com.juntaai.entity.RefreshToken;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ConflictException;
import br.com.juntaai.repository.OnboardingAnswersRepository;
import br.com.juntaai.repository.UserRepository;
import br.com.juntaai.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private OnboardingAnswersRepository onboardingAnswersRepository;
    @Mock private JwtUtil jwtUtil;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private EmailAccessCodeService emailAccessCodeService;
    @Mock private GoogleAuthService googleAuthService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, onboardingAnswersRepository, jwtUtil,
                refreshTokenService, emailAccessCodeService, googleAuthService);
    }

    private void stubTokenIssuance() {
        when(jwtUtil.generateAccessToken(any(), any(), any())).thenReturn("access-token");
        when(jwtUtil.getAccessTokenExpirationSeconds()).thenReturn(900L);

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token-value");
        when(refreshTokenService.create(any(User.class))).thenReturn(refreshToken);
    }

    @Test
    void deveEnviarCodigoDeCadastroQuandoEmailAindaNaoExiste() {
        when(userRepository.existsByEmail("ana@email.com")).thenReturn(false);

        authService.requestRegistrationCode(new RegisterCodeRequest("ana@email.com"));

        verify(emailAccessCodeService).requestCode("ana@email.com");
    }

    @Test
    void naoDeveEnviarCodigoDeCadastroParaEmailJaExistente() {
        when(userRepository.existsByEmail("ana@email.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.requestRegistrationCode(new RegisterCodeRequest("ana@email.com")))
                .isInstanceOf(ConflictException.class);

        verify(emailAccessCodeService, never()).requestCode(any());
    }

    @Test
    void deveCriarContaSoDepoisDeConfirmarCodigoEEmitirTokens() {
        RegisterVerifyRequest request = new RegisterVerifyRequest(
                "Ana", "ana@email.com", "11999998888", "123456", null, null, null);

        when(userRepository.existsByEmail("ana@email.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        stubTokenIssuance();

        var response = authService.completeRegistration(request);

        verify(emailAccessCodeService).verifyCodeForEmail("ana@email.com", "123456");
        assertThat(response.accessToken()).isEqualTo("access-token");
        verify(onboardingAnswersRepository, never()).save(any());
    }

    @Test
    void deveSalvarRespostasDeOnboardingQuandoInformadas() {
        RegisterVerifyRequest request = new RegisterVerifyRequest(
                "Bia", "bia@email.com", "11988887777", "123456",
                "Controlar melhor meus gastos", "Anoto tudo", "Economizar");

        when(userRepository.existsByEmail("bia@email.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });
        stubTokenIssuance();

        authService.completeRegistration(request);

        ArgumentCaptor<OnboardingAnswers> captor = ArgumentCaptor.forClass(OnboardingAnswers.class);
        verify(onboardingAnswersRepository).save(captor.capture());
        assertThat(captor.getValue().getQuestion1()).isEqualTo("Controlar melhor meus gastos");
    }

    @Test
    void naoDeveCriarContaSeEmailJaFoiCadastradoEntreOEnvioEAConfirmacao() {
        RegisterVerifyRequest request = new RegisterVerifyRequest(
                "Ana", "ana@email.com", "11999998888", "123456", null, null, null);

        when(userRepository.existsByEmail("ana@email.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.completeRegistration(request))
                .isInstanceOf(ConflictException.class);

        verify(emailAccessCodeService, never()).verifyCodeForEmail(any(), any());
    }

    @Test
    void deveEmitirTokensAposVerificarCodigoDeAcessoNoLogin() {
        User user = User.builder().name("Ana").email("ana@email.com").build();
        user.setId(UUID.randomUUID());

        when(emailAccessCodeService.verifyCode("ana@email.com", "123456")).thenReturn(user);
        stubTokenIssuance();

        var response = authService.verifyAccessCode(new AccessCodeVerifyRequest("ana@email.com", "123456"));

        assertThat(response.accessToken()).isEqualTo("access-token");
    }

    @Test
    void deveEmitirTokensAposLoginComGoogle() {
        User user = User.builder().name("Ana").email("ana@email.com").googleId("google-123").build();
        user.setId(UUID.randomUUID());

        when(googleAuthService.authenticate("id-token-valido")).thenReturn(user);
        stubTokenIssuance();

        var response = authService.loginWithGoogle("id-token-valido");

        assertThat(response.accessToken()).isEqualTo("access-token");
    }
}