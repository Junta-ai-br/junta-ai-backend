package br.com.juntaai.service;

import br.com.juntaai.entity.EmailAccessCode;
import br.com.juntaai.exception.ApiException;
import br.com.juntaai.repository.EmailAccessCodeRepository;
import br.com.juntaai.repository.UserRepository;
import br.com.juntaai.service.email.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailAccessCodeServiceTest {

    private static final String EMAIL = "ana@email.com";

    @Mock private EmailAccessCodeRepository codeRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailSender emailSender;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private EmailAccessCodeService service;

    @BeforeEach
    void setUp() {
        service = new EmailAccessCodeService(codeRepository, userRepository, encoder, emailSender);
        ReflectionTestUtils.setField(service, "expirationMinutes", 10L);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "maxRequestsPerWindow", 5);
        ReflectionTestUtils.setField(service, "requestWindowMinutes", 15L);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 30L);
    }

    private EmailAccessCode activeCode(String plainCode) {
        EmailAccessCode code = EmailAccessCode.builder()
                .email(EMAIL)
                .codeHash(encoder.encode(plainCode))
                .expiresAt(Instant.now().plusSeconds(600))
                .build();
        code.setId(UUID.randomUUID());
        code.setCreatedAt(Instant.now().minusSeconds(120));
        return code;
    }

    @Test
    void deveAceitarCodigoCorretoEConsumiloUmaVez() {
        EmailAccessCode code = activeCode("123456");
        when(codeRepository.findFirstByEmailAndUsedFalseOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(code));
        when(codeRepository.claimAttempt(code.getId(), 5)).thenReturn(1);
        when(codeRepository.markUsed(code.getId())).thenReturn(1);

        service.verifyCodeForEmail(EMAIL, "123456");

        verify(codeRepository).markUsed(code.getId());
    }

    @Test
    void deveRecusarCodigoErradoContandoATentativa() {
        EmailAccessCode code = activeCode("123456");
        when(codeRepository.findFirstByEmailAndUsedFalseOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(code));
        when(codeRepository.claimAttempt(code.getId(), 5)).thenReturn(1);

        assertThatThrownBy(() -> service.verifyCodeForEmail(EMAIL, "000000"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));

        verify(codeRepository).claimAttempt(code.getId(), 5);
        verify(codeRepository, never()).markUsed(any());
    }

    @Test
    void deveBloquearQuandoOLimiteDeTentativasAcabou_mesmoComCodigoCorreto() {
        EmailAccessCode code = activeCode("123456");
        when(codeRepository.findFirstByEmailAndUsedFalseOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(code));
        when(codeRepository.claimAttempt(code.getId(), 5)).thenReturn(0);

        assertThatThrownBy(() -> service.verifyCodeForEmail(EMAIL, "123456"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(codeRepository, never()).markUsed(any());
    }

    @Test
    void naoDeveAceitarCodigoExpiradoOuInexistente() {
        EmailAccessCode expired = activeCode("123456");
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(codeRepository.findFirstByEmailAndUsedFalseOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.verifyCodeForEmail(EMAIL, "123456"))
                .isInstanceOf(ApiException.class);

        verify(codeRepository, never()).claimAttempt(any(), anyInt());
    }

    @Test
    void naoDeveGerarNovoCodigoDentroDoTempoMinimoEntrePedidos() {
        EmailAccessCode last = activeCode("111111");
        last.setCreatedAt(Instant.now().minusSeconds(5));
        when(codeRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.of(last));

        assertThatThrownBy(() -> service.requestCode(EMAIL))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(emailSender, never()).sendAccessCode(anyString(), anyString());
    }

    @Test
    void naoDeveGerarNovoCodigoAlemDoTetoPorJanela() {
        when(codeRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.empty());
        when(codeRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(5L);

        assertThatThrownBy(() -> service.requestCode(EMAIL))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        verify(emailSender, never()).sendAccessCode(anyString(), anyString());
    }

    @Test
    void deveInvalidarCodigosAnterioresEEnviarOCodigoNovo() {
        when(codeRepository.findFirstByEmailOrderByCreatedAtDesc(EMAIL)).thenReturn(Optional.empty());
        when(codeRepository.countByEmailAndCreatedAtAfter(eq(EMAIL), any(Instant.class))).thenReturn(0L);

        service.requestCode(EMAIL);

        verify(codeRepository).invalidateActiveCodes(EMAIL);
        verify(emailSender).sendAccessCode(eq(EMAIL), anyString());
    }
}