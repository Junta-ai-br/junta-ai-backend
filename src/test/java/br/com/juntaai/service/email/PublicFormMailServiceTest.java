package br.com.juntaai.service.email;

import br.com.juntaai.dto.form.PublicFormRequest;
import br.com.juntaai.exception.ApiException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.MailSendException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicFormMailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final PublicFormRequest request = new PublicFormRequest("Ana", "ana@example.com", "faq", null, "Olá");
    @AfterEach void close() { validation.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"host", "port", "username", "password", "from", "to", "invalidTo", "multipleTo", "headerFrom", "console"})
    void unavailableWithoutRealConfiguration(String missing) {
        var properties = new MailProperties();
        properties.setHost("smtp.example.com"); properties.setPort(587);
        properties.setUsername("junta@example.com"); properties.setPassword("fake");
        String from = "junta@example.com", to = "contato.junta.ai@gmail.com";
        switch (missing) {
            case "host" -> properties.setHost("");
            case "port" -> properties.setPort(0);
            case "username" -> properties.setUsername("");
            case "password" -> properties.setPassword("");
            case "from" -> from = "";
            case "to" -> to = "";
            case "invalidTo" -> to = "invalid";
            case "multipleTo" -> to = "a@example.com,b@example.com";
            case "headerFrom" -> from = "junta@example.com\nBcc: x@example.com";
            case "console" -> { properties.setUsername(""); properties.setPassword(""); from = ""; }
        }
        var service = new PublicFormMailService(sender, properties, validation.getValidator(), from, to, 2);
        assertThatThrownBy(() -> service.send(FormOrigin.CONTACT, request)).isInstanceOf(ApiException.class)
                .hasNoCause().satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(sender);
    }

    @Test void validatesEvenWhenCalledOutsideController() {
        var service = new PublicFormMailService(sender, new MailProperties(), validation.getValidator(), "", "", 2);
        assertThatThrownBy(() -> service.send(FormOrigin.CONTACT,
                new PublicFormRequest("Ana", "a@example.com\n", "faq", null, "body")))
                .isInstanceOf(ApiException.class).satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(sender);
    }

    @Test void boundsConcurrentSendsAcrossFormsWithoutWaitingAndReleasesAfterSuccess() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var service = configuredService(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for test release");
            return null;
        }).when(sender).send(any(MimeMessage.class));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var sending = executor.submit(() -> service.send(FormOrigin.CONTACT, request));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                // A second form cannot consume another thread waiting for an SMTP permit.
                var feedback = new PublicFormRequest("Ana", "ana@example.com", "sugestao", null, "Olá");
                assertThatThrownBy(() -> service.send(FormOrigin.FEEDBACK, feedback))
                        .isInstanceOf(ApiException.class).satisfies(ex ->
                                assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
                verify(sender, times(1)).send(any(MimeMessage.class));
            } finally {
                release.countDown();
            }
            sending.get(5, TimeUnit.SECONDS);
            service.send(FormOrigin.CONTACT, request);
            verify(sender, times(2)).send(any(MimeMessage.class));
        }
    }

    @Test void releasesPermitAfterMailAndUnexpectedExceptions() {
        var service = configuredService(1);
        doThrow(new MailSendException("provider details"))
                .doThrow(new IllegalStateException("unexpected"))
                .doNothing().when(sender).send(any(MimeMessage.class));
        assertThatThrownBy(() -> service.send(FormOrigin.CONTACT, request))
                .isInstanceOf(ApiException.class).hasNoCause();
        assertThatThrownBy(() -> service.send(FormOrigin.CONTACT, request)).isInstanceOf(IllegalStateException.class);
        service.send(FormOrigin.CONTACT, request);
        verify(sender, times(3)).send(any(MimeMessage.class));
    }

    @Test void rejectsNonPositiveConcurrency() {
        assertThatThrownBy(() -> configuredService(0)).isInstanceOf(IllegalArgumentException.class);
    }

    private PublicFormMailService configuredService(int permits) {
        var properties = new MailProperties();
        properties.setHost("smtp.example.com"); properties.setPort(587);
        properties.setUsername("junta@example.com"); properties.setPassword("fake");
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        return new PublicFormMailService(sender, properties, validation.getValidator(),
                "junta@example.com", "contact@example.com", permits);
    }
}
