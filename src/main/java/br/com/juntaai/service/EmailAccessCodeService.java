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
import java.util.List;

/**
 * Login sem senha via código de 6 dígitos enviado por e-mail — espelha
 * src/components/Login/LoginForm.jsx (etapa "code") no frontend.
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

    @Transactional
    public void requestCode(String email) {
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

    @Transactional
    public User verifyCode(String email, String code) {
        List<EmailAccessCode> candidates = codeRepository.findByEmailAndUsedFalseOrderByCreatedAtDesc(email);

        EmailAccessCode match = candidates.stream()
                .filter(c -> c.getExpiresAt().isAfter(Instant.now()))
                .filter(c -> passwordEncoder.matches(code, c.getCodeHash()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED,
                        "Código inválido ou expirado. Solicite um novo."));

        match.setUsed(true);
        codeRepository.save(match);

        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Nenhuma conta encontrada para este e-mail. Cadastre-se primeiro."));
    }

    private String generateSixDigitCode() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }
}
