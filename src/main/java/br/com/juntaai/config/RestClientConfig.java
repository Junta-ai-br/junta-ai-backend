package br.com.juntaai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Cliente HTTP usado para conversar com o serviço de IA (junta-ai-ai).
 * Timeouts curtos e explícitos: o Backend não pode ficar bloqueado
 * esperando o CrewAI indefinidamente (ver seção "fallback" do roadmap).
 * Usa o HttpClient nativo do JDK — sem dependências extras.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient aiServiceRestClient(
            @Value("${ai.service.base-url}") String baseUrl,
            @Value("${ai.service.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${ai.service.read-timeout-ms}") int readTimeoutMs,
            @Value("${ai.service.token:}") String serviceToken) {

        // Fail closed before constructing a client that could make anonymous calls.
        // Never include the configured value in configuration errors.
        if (serviceToken == null || !serviceToken.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new IllegalStateException("AI_SERVICE_TOKEN must be configured as a valid Bearer token");
        }

        // HTTP/1.1 explícito: em http:// o JDK tenta "upgrade" para HTTP/2 (h2c)
        // e o uvicorn da IA perde o corpo da requisição (422 invalid_request).
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + serviceToken)
                .build();
    }

    /**
     * Cliente para validar ID Tokens do Google (login social). Endpoint
     * público e estável do Google — https://oauth2.googleapis.com/tokeninfo.
     */
    @Bean
    public RestClient googleRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        return RestClient.builder()
                .baseUrl("https://oauth2.googleapis.com")
                .requestFactory(requestFactory)
                .build();
    }
}
