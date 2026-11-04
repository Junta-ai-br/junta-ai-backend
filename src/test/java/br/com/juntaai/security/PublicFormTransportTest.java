package br.com.juntaai.security;

import br.com.juntaai.config.PublicFormWebConfig;
import br.com.juntaai.controller.PublicFormController;
import br.com.juntaai.exception.GlobalExceptionHandler;
import br.com.juntaai.service.email.PublicFormMailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real local Tomcat and HTTP/1.1 framing. SMTP is always mocked; no database or external services. */
@SpringBootTest(classes = PublicFormTransportTest.LocalApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.mail.host=smtp.example.invalid", "spring.mail.port=587",
        "spring.mail.username=test@example.invalid", "spring.mail.password=fake",
        "app.mail.from=test@example.invalid", "app.mail.contact-to=contact@example.invalid",
        "app.cors.allowed-origins=http://localhost", "management.health.mail.enabled=false",
        "server.forward-headers-strategy=native",
        "app.public-forms.rate-limit.per-client=100", "app.public-forms.rate-limit.global=1000"})
class PublicFormTransportTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({PublicFormController.class, PublicFormMailService.class, PublicFormWebConfig.class,
            PublicFormRateLimiter.class, SecurityConfig.class, JwtFilter.class, GlobalExceptionHandler.class})
    static class LocalApplication {}

    @LocalServerPort int port;
    @MockBean JavaMailSender sender;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService users;
    @SpyBean PublicFormRateLimiter limiter;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void prepareMail() {
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    static Stream<Object[]> bodies() {
        return Stream.of("/contact", "/feedback").flatMap(path -> Stream.of(false, true)
                .flatMap(chunked -> Stream.of(65535, 65536, 65537).map(size -> new Object[]{path, chunked, size})));
    }

    @ParameterizedTest @MethodSource("bodies")
    void enforcesActualBytesIncludingExtraFields(String path, boolean chunked, int size) throws Exception {
        String prefix = "{\"name\":\"Ana\",\"email\":\"ana@example.com\",\"subject\":\"sugestao\",\"message\":\"Olá\\n😀\",\"extra\":\"";
        String suffix = "\"}";
        int padding = size - prefix.getBytes(StandardCharsets.UTF_8).length - suffix.length();
        byte[] body = (prefix + "x".repeat(padding) + suffix).getBytes(StandardCharsets.UTF_8);
        var publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(publisher).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(size > 65536 ? 413 : 200);
        if (size > 65536) {
            verify(sender, never()).send(any(MimeMessage.class));
            assertThat(response.body()).contains("Requisição grande demais.");
        } else {
            verify(sender).send(any(MimeMessage.class));
            assertThat(response.body()).contains("\"status\":\"sent\"");
        }
    }

    @Test void rejectsExcessTrailingBytesEvenAfterACompleteJsonObject() throws Exception {
        String json = "{\"name\":\"Ana\",\"email\":\"ana@example.com\",\"subject\":\"sugestao\",\"message\":\"Olá\"}";
        byte[] body = (json + " ".repeat(65537 - json.getBytes(StandardCharsets.UTF_8).length)).getBytes(StandardCharsets.UTF_8);
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/contact"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test void appliesTheSameLimitToAnEncodedPublicRoute() throws Exception {
        byte[] body = new byte[65537];
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/%63ontact"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test void nativeTomcatUsesRightmostUntrustedAddressFromATrustedLocalPeer() throws Exception {
        // Local loopback is trusted by Tomcat's defaults. This proves server processing,
        // not Azure's actual topology, sanitization, or production configuration.
        String json = "{\"name\":\"Ana\",\"email\":\"ana@example.com\",\"subject\":\"sugestao\",\"message\":\"Olá\"}";
        var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/contact"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.9, 198.51.100.10")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        verify(limiter).acquire("198.51.100.10");
    }
}
