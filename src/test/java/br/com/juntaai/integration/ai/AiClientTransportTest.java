package br.com.juntaai.integration.ai;

import br.com.juntaai.config.RestClientConfig;
import br.com.juntaai.entity.CategoryType;
import br.com.juntaai.integration.ai.model.CategoryCatalogEntry;
import br.com.juntaai.integration.ai.model.FinancialStateSnapshot;
import java.util.Map;
import br.com.juntaai.integration.ai.model.AIServiceRequest;
import br.com.juntaai.integration.ai.model.ConversationContext;
import br.com.juntaai.integration.ai.model.ProcessingResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AiClientTransportTest {
    private record Capture(String method, String contentType, String contentLength,
                           String transferEncoding, String upgrade, byte[] body) {}

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sendsNonemptyJsonOverRealJdkTransport(boolean useAiClient) throws Exception {
        var captures = new ArrayBlockingQueue<Capture>(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/conversations/process", exchange -> {
            var headers = exchange.getRequestHeaders();
            captures.add(new Capture(exchange.getRequestMethod(), headers.getFirst("Content-Type"),
                    headers.getFirst("Content-Length"), headers.getFirst("Transfer-Encoding"),
                    headers.getFirst("Upgrade"), exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"contract_version\":\"1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        try {
            var rest = new RestClientConfig().aiServiceRestClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), 3000, 5000);
            var category = new CategoryCatalogEntry(UUID.randomUUID(), "Synthetic transport category", CategoryType.EXPENSE);
            var snapshot = new FinancialStateSnapshot(null, Map.of("category_catalog", List.of(category)),
                    Map.of(), List.of(), List.of(), Map.of(), Map.of());
            var request = new AIServiceRequest(UUID.randomUUID().toString(), "1", UUID.randomUUID().toString(),
                    "synthetic transport diagnostic", ConversationContext.of(List.of()), null, null, snapshot, null);
            if (useAiClient) {
                var client = new AiClient(rest);
                ReflectionTestUtils.setField(client, "processPath", "/v1/conversations/process");
                client.process(request);
            } else {
                rest.post().uri("/v1/conversations/process").body(request).retrieve().body(ProcessingResult.class);
            }
            Capture capture = captures.poll(5, TimeUnit.SECONDS);
            assertThat(capture).isNotNull();
            System.out.printf("transport_capture ai_client=%s method=%s content_type=%s content_length=%s transfer_encoding=%s upgrade=%s bytes=%d%n",
                    useAiClient, capture.method(), capture.contentType(), capture.contentLength(),
                    capture.transferEncoding(), capture.upgrade(), capture.body().length);
            assertThat(capture.method()).isEqualTo("POST");
            assertThat(capture.contentType()).startsWith("application/json");
            assertThat(capture.body().length).isPositive();
            if (capture.contentLength() != null) {
                assertThat(Integer.parseInt(capture.contentLength())).isEqualTo(capture.body().length);
            } else {
                assertThat(capture.transferEncoding()).isEqualToIgnoringCase("chunked");
            }
            var json = new ObjectMapper().readTree(capture.body());
            assertThat(json.isObject()).isTrue();
            var catalog = json.path("financial_state").path("current_state").path("category_catalog");
            assertThat(catalog.isArray()).isTrue();
            assertThat(catalog.size()).isEqualTo(1);
            assertThat(catalog.get(0).size()).isEqualTo(3);
            assertThat(catalog.get(0).path("id").asText()).isEqualTo(category.id().toString());
            assertThat(catalog.get(0).path("name").asText()).isEqualTo(category.name());
            assertThat(catalog.get(0).path("type").asText()).isEqualTo("EXPENSE");
            assertThat(json.path("request_id").asText()).isEqualTo(request.request_id());
            assertThat(json.path("conversation_id").asText()).isEqualTo(request.conversation_id());
            assertThat(json.path("user_message").asText()).isEqualTo(request.user_message());
        } finally {
            server.stop(0);
        }
    }
}
