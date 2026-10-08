package br.com.juntaai.service.email;

import br.com.juntaai.dto.form.PublicFormRequest;
import br.com.juntaai.exception.ApiException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@Service
public class PublicFormMailService {
    private final JavaMailSender sender;
    private final MailProperties properties;
    private final Validator validator;
    private final String from;
    private final String to;

    public PublicFormMailService(JavaMailSender sender, MailProperties properties, Validator validator,
                                 @Value("${app.mail.from:}") String from,
                                 @Value("${app.mail.contact-to:}") String to) {
        this.sender = sender;
        this.properties = properties;
        this.validator = validator;
        this.from = from;
        this.to = to;
    }

    public void send(FormOrigin origin, PublicFormRequest request) {
        if (!validator.validate(request).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Dados inválidos no formulário.");
        }
        String subject = origin.subjectLabel(request);
        if (!StringUtils.hasText(properties.getHost()) || properties.getPort() == null
                || properties.getPort() < 1 || properties.getPort() > 65535
                || !StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(properties.getPassword())
                || !validMailbox(from) || !validMailbox(to)) {
            throw unavailable();
        }
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setReplyTo(request.email());
            helper.setSubject("[" + origin.label() + " Junta.ai] " + subject);
            helper.setText("Origem: " + origin.label() + "\nNome: " + request.name()
                    + "\nE-mail: " + request.email() + "\nAssunto: " + subject
                    + "\n\nMensagem:\n" + request.message(), false);
            sender.send(message);
        } catch (MailException | MessagingException exception) {
            // Never propagate or log provider errors: they can include secrets and message content.
            throw unavailable();
        }
    }

    private static boolean validMailbox(String value) {
        if (!StringUtils.hasText(value) || value.contains("\r") || value.contains("\n")) return false;
        try {
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            return address.getPersonal() == null && value.equals(address.getAddress()) && value.contains("@");
        } catch (MessagingException exception) {
            return false;
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Não foi possível enviar a mensagem agora. Tente novamente em instantes.");
    }
}
