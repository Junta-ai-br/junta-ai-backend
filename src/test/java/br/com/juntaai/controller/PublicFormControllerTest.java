package br.com.juntaai.controller;

import br.com.juntaai.config.PublicFormWebConfig;
import br.com.juntaai.exception.GlobalExceptionHandler;
import br.com.juntaai.security.JwtFilter;
import br.com.juntaai.security.JwtUtil;
import br.com.juntaai.security.PublicFormRateLimiter;
import br.com.juntaai.security.SecurityConfig;
import br.com.juntaai.service.email.PublicFormMailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PublicFormController.class, properties = {
        "spring.mail.host=smtp.example.com", "spring.mail.port=587",
        "spring.mail.username=junta@example.com", "spring.mail.password=fake",
        "app.mail.from=junta@example.com", "app.mail.contact-to=contato.junta.ai@gmail.com",
        "app.cors.allowed-origins=https://junta-ai.vercel.app",
        "app.public-forms.rate-limit.per-client=2", "app.public-forms.rate-limit.global=1000"})
@Import({SecurityConfig.class, JwtFilter.class, PublicFormMailService.class,
        PublicFormWebConfig.class, PublicFormRateLimiter.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(MailProperties.class)
class PublicFormControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MailProperties mailProperties;
    @MockBean JavaMailSender sender;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService users;
    @MockBean org.springframework.data.jpa.mapping.JpaMetamodelMappingContext jpaMappingContext;
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    private String client;
    private MimeMessage message;

    @BeforeEach
    void setup() {
        client = "192.0.2." + CLIENTS.incrementAndGet();
        message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
    }

    private Map<String, Object> input(String subject) {
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "  Ana  ");
        input.put("email", " ana@example.com ");
        input.put("subject", subject);
        input.put("message", "  Olá!\nSegunda linha.  ");
        return input;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/contact|faq|Não encontrei minha dúvida na FAQ", "/contact|junta|Dúvida sobre o Junta.ai",
            "/contact|sugestao|Sugestão ou ideia", "/contact|parceria|Parceria ou colaboração",
            "/contact|imprensa|Imprensa", "/feedback|experiencia|Experiência com o Junta.ai",
            "/feedback|sugestao|Sugestão ou nova ideia", "/feedback|problema|Encontrei um problema",
            "/feedback|duvida|Algo não ficou claro", "/feedback|privacidade|Privacidade e segurança",
            "/feedback|financeiro|Recursos financeiros", "/feedback|assistente|Assistente / IA",
            "/contact|outro|Assunto personalizado", "/feedback|outro|Assunto personalizado"})
    void sendsAnonymouslyWithFixedHeaders(String path, String code, String label) throws Exception {
        var input = input(" " + code + " ");
        input.put("subjectOther", code.equals("outro") ? " Assunto personalizado " : "ignored\n" + "x".repeat(200));
        input.put("to", "attacker@example.com");
        input.put("from", "attacker@example.com");
        mvc.perform(post(path).with(r -> { r.setRemoteAddr(client); return r; })
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"sent\"}"));
        verify(sender).send(message);
        message.saveChanges();
        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("contato.junta.ai@gmail.com");
        assertThat(message.getFrom()).extracting(Object::toString).containsExactly("junta@example.com");
        assertThat(message.getReplyTo()).extracting(Object::toString).containsExactly("ana@example.com");
        String origin = path.equals("/contact") ? "Contato" : "Feedback";
        assertThat(message.getSubject()).isEqualTo("[" + origin + " Junta.ai] " + label);
        assertThat(message.getContent().toString()).contains("Origem: " + origin, "Nome: Ana", "E-mail: ana@example.com",
                "Assunto: " + label, "Olá!\nSegunda linha.");
        assertThat(message.getContentType()).containsIgnoringCase("text/plain").containsIgnoringCase("charset=UTF-8");
    }

    static Stream<Object[]> invalidFields() {
        return Stream.of(new Object[]{"name", null}, new Object[]{"name", "   "},
                new Object[]{"name", "x".repeat(121)}, new Object[]{"name", "\nAna"},
                new Object[]{"email", "bad"}, new Object[]{"email", "ana@example.com\r\nBcc: x@y.com"},
                new Object[]{"email", "x".repeat(255) + "@example.com"}, new Object[]{"email", null},
                new Object[]{"subject", "experiencia"}, new Object[]{"subject", "unknown"},
                new Object[]{"subject", "\nfaq"}, new Object[]{"subject", "x".repeat(33)},
                new Object[]{"subject", null}, new Object[]{"message", " \n "},
                new Object[]{"message", "x".repeat(10001)}, new Object[]{"message", null});
    }

    @ParameterizedTest @MethodSource("invalidFields")
    void rejectsInvalidFields(String field, Object value) throws Exception {
        var input = input("faq"); input.put(field, value);
        mvc.perform(post("/contact").with(r -> { r.setRemoteAddr(client); return r; })
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "   ", "bad\nheader", "bad\rheader"})
    void rejectsInvalidOther(String other) throws Exception {
        var input = input("outro"); input.put("subjectOther", other);
        mvc.perform(post("/feedback").with(r -> { r.setRemoteAddr(client); return r; })
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test void rejectsMissingOtherAndCrossFormSubject() throws Exception {
        for (String subject : new String[]{"outro", "faq"}) {
            mvc.perform(post("/feedback").with(r -> { r.setRemoteAddr(client); return r; })
                            .contentType("application/json").content(mapper.writeValueAsString(input(subject))))
                    .andExpect(status().isBadRequest());
        }
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test void returnsSafe503AfterSmtpFailure() throws Exception {
        doThrow(new MailSendException("secret password and message")).when(sender).send(any(MimeMessage.class));
        mvc.perform(post("/contact").with(r -> { r.setRemoteAddr(client); return r; })
                        .contentType("application/json").content(mapper.writeValueAsString(input("faq"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.mensagem").value("Não foi possível enviar a mensagem agora. Tente novamente em instantes."));
    }

    @Test void sharesQuotaAndIgnoresSpoofedForwardingHeaders() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(post(i == 0 ? "/contact" : "/feedback")
                            .with(r -> { r.setRemoteAddr(client); return r; })
                            .header("X-Forwarded-For", "198.51.100." + i)
                            .contentType("application/json").content("{}"))
                    .andExpect(status().is(i < 2 ? 400 : 429));
        }
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test void protectsPrivateRoutesAndOtherMethods() throws Exception {
        for (String path : new String[]{"/contact", "/feedback", "/categories", "/dashboard", "/admin/test"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/categories").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void allowsOnlyConfiguredCorsPreflightWithoutUsingQuota() throws Exception {
        for (String path : new String[]{"/contact", "/feedback"}) {
            for (int i = 0; i < 3; i++) {
                mvc.perform(options(path).header("Origin", "https://junta-ai.vercel.app")
                                .header("Access-Control-Request-Method", "POST")
                                .header("Access-Control-Request-Headers", "content-type"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Access-Control-Allow-Origin", "https://junta-ai.vercel.app"));
            }
            mvc.perform(options(path).header("Origin", "https://evil.example")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden());
        }
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test void malformedJsonHasSafe400() throws Exception {
        mvc.perform(post("/contact").contentType("application/json").content("{bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.mensagem").value("JSON inválido."));
    }

    @Test void absentSmtpConfigurationReturns503WithoutSending() throws Exception {
        String password = mailProperties.getPassword();
        try {
            mailProperties.setPassword("");
            mvc.perform(post("/feedback").with(r -> { r.setRemoteAddr(client); return r; })
                            .contentType("application/json").content(mapper.writeValueAsString(input("experiencia"))))
                    .andExpect(status().isServiceUnavailable());
            verify(sender, never()).send(any(MimeMessage.class));
        } finally {
            mailProperties.setPassword(password);
        }
    }

    @Test void rejectsOversizedOther() throws Exception {
        var input = input("outro"); input.put("subjectOther", "x".repeat(161));
        mvc.perform(post("/contact").with(r -> { r.setRemoteAddr(client); return r; })
                        .contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isBadRequest());
        verify(sender, never()).send(any(MimeMessage.class));
    }
}
