package br.com.juntaai.config;

import br.com.juntaai.service.email.ConsoleEmailSender;
import br.com.juntaai.service.email.EmailSender;
import br.com.juntaai.service.email.GmailEmailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class EmailSenderConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(EmailSenderConfig.class, EmailPackageScan.class)
            .withPropertyValues("spring.mail.host=smtp.gmail.com", "spring.mail.port=587",
                    "auth.access-code.expiration-minutes=7");

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = ConsoleEmailSender.class)
    static class EmailPackageScan {
    }

    @Test
    void deveRegistrarSomenteGmailComConfiguracaoCompleta() {
        runner.withPropertyValues("spring.mail.username=junta@example.com",
                        "spring.mail.password=senha-ficticia", "app.mail.from=junta@example.com")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EmailSender.class);
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(GmailEmailSender.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "dev", "local,dev"})
    void deveRegistrarSomenteConsoleComConfiguracaoAusenteEmDesenvolvimento(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(EmailSender.class);
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(ConsoleEmailSender.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "production", "docker", "local,production", "dev,prod", "default"})
    void deveImpedirConsoleForaDeDesenvolvimentoExclusivo(String profiles) {
        runner.withPropertyValues("spring.profiles.active=" + profiles).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @Test
    void deveFalharSemProfileExplicitoEConfiguracaoAusente() {
        runner.run(context -> assertThat(context).hasFailed().getFailure()
                .hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.mail.username=junta@example.com", "spring.mail.password=senha-ficticia",
            "app.mail.from=junta@example.com"})
    void deveFalharComCredenciaisParciaisMesmoEmDesenvolvimento(String partialProperty) {
        runner.withPropertyValues("spring.profiles.active=local", partialProperty).run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.mail.port=0", "spring.mail.port=65536",
            "spring.mail.username=", "spring.mail.password=", "app.mail.from= "})
    void deveFalharComConfiguracaoIncompleta(String invalidProperty) {
        runner.withPropertyValues("spring.mail.username=junta@example.com",
                        "spring.mail.password=senha-ficticia", "app.mail.from=junta@example.com")
                .withPropertyValues(invalidProperty).run(context ->
                        assertThat(context).hasFailed().getFailure()
                                .hasRootCauseInstanceOf(IllegalStateException.class));
    }
}
