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
            @Value("${ai.service.read-timeout-ms}") int readTimeoutMs) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
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
