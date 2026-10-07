package br.com.juntaai.service.email;

import br.com.juntaai.exception.ApiException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;

/** Entrega o código recebido via SMTP; o ciclo de vida do OTP pertence ao backend. */
public class GmailEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;
    private final long expirationMinutes;

    public GmailEmailSender(JavaMailSender mailSender, String from, long expirationMinutes) {
        this.mailSender = mailSender;
        this.from = from;
        this.expirationMinutes = expirationMinutes;
    }

    @Override
    public void sendAccessCode(String email, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("Seu código de acesso ao Junta.ai");
            helper.setText("Seu código de acesso ao Junta.ai é: " + code
                    + "\n\nEle vale por " + expirationMinutes + " minutos."
                    + "\n\nSe você não pediu esse código, pode ignorar este e-mail.");
            mailSender.send(message);
        } catch (MailException | MessagingException exception) {
            // Não propagar a causa do provedor: ela pode conter a mensagem ou credenciais.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível enviar o código por e-mail agora. Tente novamente em instantes.");
        }
    }
}
