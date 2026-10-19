package br.com.juntaai.service;

import br.com.juntaai.entity.RefreshToken;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ApiException;
import br.com.juntaai.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock private RefreshTokenRepository repository;

    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repository);
        ReflectionTestUtils.setField(service, "refreshTokenExpirationMs", 604_800_000L);
        user = User.builder().name("Ana").email("ana@email.com").build();
        user.setId(UUID.randomUUID());
    }

    private RefreshToken stored(String raw, boolean revoked, Instant expiry) {
        RefreshToken token = RefreshToken.builder()
                .user(user).token(RefreshTokenService.hash(raw)).expiryDate(expiry).revoked(revoked).build();
        token.setId(UUID.randomUUID());
        return token;
    }

    @Test
    void deveGuardarSomenteOHashEDevolverOValorOriginal() {
        String raw = service.create(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(captor.capture());

        assertThat(raw).hasSizeGreaterThanOrEqualTo(40);
        assertThat(captor.getValue().getToken()).isNotEqualTo(raw);
        assertThat(captor.getValue().getToken()).isEqualTo(RefreshTokenService.hash(raw));
    }

    @Test
    void deveGirarOTokenValido() {
        RefreshToken token = stored("abc", false, Instant.now().plusSeconds(60));
        when(repository.findByToken(RefreshTokenService.hash("abc"))).thenReturn(Optional.of(token));
        when(repository.revokeIfActive(token.getId())).thenReturn(1);

        assertThat(service.validateAndRotate("abc")).isSameAs(user);
    }

    @Test
    void deveEncerrarTodasAsSessoesQuandoUmTokenJaUsadoReaparece() {
        RefreshToken token = stored("abc", true, Instant.now().plusSeconds(60));
        when(repository.findByToken(RefreshTokenService.hash("abc"))).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.validateAndRotate("abc")).isInstanceOf(ApiException.class);

        verify(repository).deleteAllByUserId(user.getId());
    }

    @Test
    void naoDeveAceitarTokenExpiradoOuDesconhecido() {
        RefreshToken expired = stored("old", false, Instant.now().minusSeconds(1));
        when(repository.findByToken(RefreshTokenService.hash("old"))).thenReturn(Optional.of(expired));
        when(repository.findByToken(RefreshTokenService.hash("nope"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateAndRotate("old")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.validateAndRotate("nope")).isInstanceOf(ApiException.class);
        verify(repository, never()).revokeIfActive(any());
    }

    @Test
    void naoDeveAceitarQuandoOutraRequisicaoGastouOTokenNoMesmoInstante() {
        RefreshToken token = stored("abc", false, Instant.now().plusSeconds(60));
        when(repository.findByToken(RefreshTokenService.hash("abc"))).thenReturn(Optional.of(token));
        when(repository.revokeIfActive(token.getId())).thenReturn(0);

        assertThatThrownBy(() -> service.validateAndRotate("abc")).isInstanceOf(ApiException.class);
    }
}