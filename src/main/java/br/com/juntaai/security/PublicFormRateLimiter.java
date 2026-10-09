package br.com.juntaai.security;

import br.com.juntaai.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Shared quota for both forms; bounded, synchronized fixed windows per instance. */
@Component
public class PublicFormRateLimiter {
    private final int perClient;
    private final int global;
    private final int maxClients;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> clients = new HashMap<>();
    private Window total;

    @Autowired
    public PublicFormRateLimiter(@Value("${app.public-forms.rate-limit.per-client:5}") int perClient,
                                 @Value("${app.public-forms.rate-limit.global:100}") int global,
                                 @Value("${app.public-forms.rate-limit.max-clients:10000}") int maxClients,
                                 @Value("${app.public-forms.rate-limit.window-seconds:600}") long seconds) {
        this(perClient, global, maxClients, seconds, Clock.systemUTC());
    }

    PublicFormRateLimiter(int perClient, int global, int maxClients, long seconds, Clock clock) {
        if (perClient < 1 || global < 1 || maxClients < 1 || seconds < 1 || seconds > 86400) {
            throw new IllegalArgumentException("Limites dos formulários devem ser positivos; janela máxima de 86400 segundos.");
        }
        this.perClient = perClient;
        this.global = global;
        this.maxClients = maxClients;
        this.windowMillis = seconds * 1000;
        this.clock = clock;
    }

    public synchronized void acquire(String client) {
        long now = clock.millis();
        clients.values().removeIf(window -> now - window.start >= windowMillis);
        if (total == null || now - total.start >= windowMillis) total = new Window(now);
        Window window = clients.get(client);
        if (total.count >= global || (window != null && window.count >= perClient)
                || (window == null && clients.size() >= maxClients)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Limite de requisições atingido. Tente novamente após a janela de limite.");
        }
        if (window == null) {
            window = new Window(now);
            clients.put(client, window);
        }
        window.count++;
        total.count++;
    }

    private static class Window {
        final long start;
        int count;
        Window(long start) { this.start = start; }
    }
}
