package br.com.juntaai.service.email;

/**
 * Porta de saída para envio de e-mail. Hoje só existe a implementação de
 * console (ConsoleEmailSender) — quando o time decidir o provedor real
 * (Azure Communication Services, SendGrid, etc.), basta criar outra
 * implementação e trocar via profile/configuração, sem tocar em
 * EmailAccessCodeService.
 */
public interface EmailSender {
    void sendAccessCode(String email, String code);
}
