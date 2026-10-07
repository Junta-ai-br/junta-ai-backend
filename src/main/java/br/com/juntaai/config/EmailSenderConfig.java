package br.com.juntaai.config;

import br.com.juntaai.service.email.ConsoleEmailSender;
import br.com.juntaai.service.email.EmailSender;
import br.com.juntaai.service.email.GmailEmailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

import java.util.Arrays;

@Configuration(proxyBeanMethods = false)
public class EmailSenderConfig {

    @Bean
    public EmailSender emailSender(JavaMailSender mailSender, MailProperties mailProperties,
                                  Environment environment,
                                  @Value("${app.mail.from:}") String from,
                                  @Value("${auth.access-code.expiration-minutes}") long expirationMinutes) {
        boolean complete = StringUtils.hasText(mailProperties.getHost())
                && mailProperties.getPort() != null && mailProperties.getPort() > 0
                && mailProperties.getPort() <= 65535
                && StringUtils.hasText(mailProperties.getUsername())
                && StringUtils.hasText(mailProperties.getPassword())
                && StringUtils.hasText(from);
        if (complete) {
            return new GmailEmailSender(mailSender, from, expirationMinutes);
        }

        String[] profiles = environment.getActiveProfiles();
        boolean localOnly = profiles.length > 0
                && Arrays.stream(profiles).allMatch(profile -> profile.equals("local") || profile.equals("dev"));
        boolean partiallyConfigured = StringUtils.hasText(mailProperties.getUsername())
                || StringUtils.hasText(mailProperties.getPassword()) || StringUtils.hasText(from);
        if (localOnly && !partiallyConfigured) {
            return new ConsoleEmailSender();
        }

        throw new IllegalStateException("Configure o SMTP com host, porta, usuário, senha e remetente. "
                + "O envio pelo console exige profile local ou dev exclusivo e credenciais SMTP ausentes.");
    }
}
