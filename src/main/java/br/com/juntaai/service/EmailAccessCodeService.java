package br.com.juntaai.service;

import br.com.juntaai.entity.EmailAccessCode;
import br.com.juntaai.entity.User;
import br.com.juntaai.exception.ApiException;
import br.com.juntaai.repository.EmailAccessCodeRepository;
import br.com.juntaai.repository.UserRepository;
import br.com.juntaai.service.email.EmailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

/**
 * Códigos de 6 dígitos enviados por e-mail (cadastro e login).
 *
 * Como o espaço de um código é de só 1.000.000 de combinações, a segurança
 * vem dos LIMITES, não do código em si:
 *  - só o código mais recente vale (pedir outro invalida os anteriores);
 *  - no máximo `max-attempts` tentativas por código (contagem atômica no banco);
 *  - tempo mínimo entre pedidos e teto de pedidos por janela, por e-mail
 *    (evita spam de e-mail e crescimento descontrolado da tabela).
 */
@Service
@RequiredArgsConstructor
public class EmailAccessCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailAccessCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;

    @Value("${auth.access-code.expiration-minutes:10}")
    private long expirationMinutes;

    @Value("${auth.access-code.max-attempts:5}")
    private int maxAttempts;

    @Value("${auth.access-code.max-requests-per-window:5}")
    private int maxRequestsPerWindow;

    @Value("${auth.access-code.request-window-minutes:15}")
    private long requestWindowMinutes;

    @Value("${auth.access-code.resend-cooldown-seconds:30}")
    private long resendCooldownSeconds;

    @Transactional
    public void requestCode(String email) {
        enforceRequestLimits(email);

        // Só o código mais novo vale.
        codeRepository.invalidateActiveCodes(email);

        String code = generateSixDigitCode();

        EmailAccessCode accessCode = EmailAccessCode.builder()
                .email(email)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(Instant.now().plusSeconds(expirationMinutes * 60))
                .used(false)
                .build();

        codeRepository.save(accessCode);
        emailSender.sendAccessCode(email, code);
    }

    /**
     * Só valida e consome o código — não exige usuário existente. Usado
     * no cadastro (onde o usuário ainda não existe) e, por dentro, no
     * login por código (verifyCode).
     *
     * noRollbackFor: a tentativa errada precisa ficar registrada no banco
     * mesmo quando lançamos a exceção 401; sem isso o rollback desfaria a
     * contagem e o limite de tentativas nunca valeria.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void verifyCodeForEmail(String email, String code) {
        Optional<EmailAccessCode> active = codeRepository
                .findFirstByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .filter(c -> c.getExpiresAt().isAfter(Instant.now()));

        EmailAccessCode candidate = active.orElseThrow(this::invalidCode);

        // Reserva a tentativa ANTES de comparar. Se o limite já foi atingido, ninguém passa.
        if (codeRepository.claimAttempt(candidate.getId(), maxAttempts) == 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas com este código. Solicite um novo código.");
        }

        if (!passwordEncoder.matches(code, candidate.getCodeHash())) {
            throw invalidCode();
        }

        // Consumo atômico: se duas requisições acertarem juntas, só uma passa.
        if (codeRepository.markUsed(candidate.getId()) == 0) {
            throw invalidCode();
        }
    }

    /** Login por código: exige que já exista uma conta para esse e-mail. */
    @Transactional(noRollbackFor = ApiException.class)
    public User verifyCode(String email, String code) {
        verifyCodeForEmail(email, code);

        return userRepository.findByEmail(email)
                .orElseThrow(this::invalidCode);
    }

    private void enforceRequestLimits(String email) {
        Instant now = Instant.now();

        if (resendCooldownSeconds > 0) {
            codeRepository.findFirstByEmailOrderByCreatedAtDesc(email)
                    .filter(last -> last.getCreatedAt() != null
                            && last.getCreatedAt().plusSeconds(resendCooldownSeconds).isAfter(now))
                    .ifPresent(last -> {
                        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                                "Aguarde alguns segundos antes de pedir um novo código.");
                    });
        }

        long recent = codeRepository.countByEmailAndCreatedAtAfter(
                email, now.minusSeconds(requestWindowMinutes * 60));
        if (recent >= maxRequestsPerWindow) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitos códigos solicitados para este e-mail. Tente novamente mais tarde.");
        }
    }

    /** Mensagem única para "não existe", "expirou" e "errado": não revela qual foi o caso. */
    private ApiException invalidCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Código inválido ou expirado. Solicite um novo.");
    }

    private String generateSixDigitCode() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }
}