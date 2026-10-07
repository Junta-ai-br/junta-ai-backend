package br.com.juntaai.service.email;

import lombok.extern.slf4j.Slf4j;

/**
 * Registra o código no log para desenvolvimento local. Instanciada apenas
 * por EmailSenderConfig em profile local/dev sem credenciais SMTP.
 * NÃO usar em produção.
 */
@Slf4j
public class ConsoleEmailSender implements EmailSender {

    @Override
    public void sendAccessCode(String email, String code) {
        log.info("[EmailSender de desenvolvimento] Código de acesso para {}: {}", email, code);
    }
}
