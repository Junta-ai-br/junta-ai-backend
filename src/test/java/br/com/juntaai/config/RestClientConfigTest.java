package br.com.juntaai.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientConfigTest {

    @Test
    void aiClientSendsBodyOverHttp11WithoutUpgradeHeader() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestClient client = new RestClientConfig().aiServiceRestClient(baseUrl, 2000, 2000, "synthetic-ai-service-token");

            client.post().uri("/echo").body("{\"a\":1}").retrieve().toBodilessEntity();

            assertThat(upgrade.get()).isNull();
            assertThat(body.get()).isEqualTo("{\"a\":1}");
            assertThat(authorization.get()).isEqualTo("Bearer synthetic-ai-service-token");
            assertThat(body.get()).doesNotContain("synthetic-ai-service-token", "Authorization");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aiClientSendsBearerTokenWhenConfigured() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestClient client = new RestClientConfig().aiServiceRestClient(baseUrl, 2000, 2000, "token-de-teste");

            client.post().uri("/echo").body("{\"a\":1}").retrieve().toBodilessEntity();

            assertThat(authorization.get()).isEqualTo("Bearer token-de-teste");
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void aiClientFailsClosedBeforeSendingWhenTokenIsMissingOrBlank(String token) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThatThrownBy(() -> new RestClientConfig().aiServiceRestClient(baseUrl, 2000, 2000, token)
                    .post().uri("/echo").body("{\"a\":1}").retrieve().toBodilessEntity())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AI_SERVICE_TOKEN must be configured as a valid Bearer token");
            assertThat(requests.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "secret with spaces", "secret\r\ninjected: header"})
    void missingOrMalformedConfigurationCannotCreateAnonymousClient(String token) {
        assertThatThrownBy(() -> new RestClientConfig().aiServiceRestClient(
                "http://127.0.0.1:1", 2000, 2000, token))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AI_SERVICE_TOKEN must be configured as a valid Bearer token");
    }

    @Test
    void tokenDoesNotReachGoogleClientOrAnotherClient() {
        var config = new RestClientConfig();
        var ai = config.aiServiceRestClient("http://ai.invalid", 2000, 2000, "synthetic-ai-service-token");
        var googleBuilder = config.googleRestClient().mutate();
        var googleServer = org.springframework.test.web.client.MockRestServiceServer.bindTo(googleBuilder).build();
        googleServer.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "https://oauth2.googleapis.com/tokeninfo"))
                .andExpect(request -> assertThat(request.getHeaders().get("Authorization")).isNull())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        googleBuilder.build().get().uri("/tokeninfo").retrieve().toBodilessEntity();
        googleServer.verify();
        assertThat(ai).isNotNull();
    }
}
