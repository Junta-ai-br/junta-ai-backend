package br.com.juntaai.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientConfigTest {

    @Test
    void aiClientSendsBodyOverHttp11WithoutUpgradeHeader() throws Exception {
        AtomicReference<String> upgrade = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            upgrade.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RestClient client = new RestClientConfig().aiServiceRestClient(baseUrl, 2000, 2000);

            client.post().uri("/echo").body("{\"a\":1}").retrieve().toBodilessEntity();

            assertThat(upgrade.get()).isNull();
            assertThat(body.get()).isEqualTo("{\"a\":1}");
        } finally {
            server.stop(0);
        }
    }
}