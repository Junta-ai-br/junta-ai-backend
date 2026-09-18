package br.com.juntaai.service.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Implementação temporária: só registra o código no log em vez de
 * enviar e-mail de verdade. Existe para permitir testar o fluxo de login
 * localmente antes de o provedor de e-mail (Azure Communication Services
 * ou equivalente) estar configurado. NÃO usar em produção.
 */
@Slf4j
@Component
public class ConsoleEmailSender implements EmailSender {

    @Override
    public void sendAccessCode(String email, String code) {
        log.info("[EmailSender de desenvolvimento] Código de acesso para {}: {}", email, code);
    }
}
