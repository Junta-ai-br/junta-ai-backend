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
        var service = new PublicFormMailService(sender, properties, validation.getValidator(), from, to);
        assertThatThrownBy(() -> service.send(FormOrigin.CONTACT, request)).isInstanceOf(ApiException.class)
                .hasNoCause().satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(sender);
    }

    @Test void validatesEvenWhenCalledOutsideController() {
        var service = new PublicFormMailService(sender, new MailProperties(), validation.getValidator(), "", "");
        assertThatThrownBy(() -> service.send(FormOrigin.CONTACT,
                new PublicFormRequest("Ana", "a@example.com\n", "faq", null, "body")))
                .isInstanceOf(ApiException.class).satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(sender);
    }
}
