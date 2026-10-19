package br.com.juntaai.service;

import br.com.juntaai.repository.EmailAccessCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Apaga códigos de e-mail e refresh tokens vencidos. Sem isso as duas
 * tabelas só crescem (cada login/pedido de código cria uma linha), o que
 * piora as consultas com o tempo. Rodar em mais de uma réplica é inofensivo.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredCredentialsCleanup {

    private final EmailAccessCodeRepository codeRepository;
    private final RefreshTokenService refreshTokenService;

    @Scheduled(cron = "${app.cleanup.cron:0 15 3 * * *}")
    @Transactional
    public void run() {
        int codes = codeRepository.deleteExpiredBefore(Instant.now().minus(1, ChronoUnit.DAYS));
        int tokens = refreshTokenService.deleteExpired();
        log.info("Limpeza de credenciais vencidas: {} códigos e {} refresh tokens removidos.", codes, tokens);
    }
}