package br.com.juntaai.security;

import br.com.juntaai.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita quantas mensagens por minuto cada usuário pode mandar para o chat.
 * Cada mensagem aciona o serviço de IA (lento e com custo por uso): sem
 * limite, um único usuário (ou um script) poderia ocupar a IA e gerar
 * custo para todos. Janela fixa em memória, por instância.
 */
@Component
public class ChatRateLimiter {

    private final int perWindow;
    private final long windowMillis;
    private final int maxUsers;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ChatRateLimiter(@Value("${app.chat-rate-limit.per-user:20}") int perWindow,
                           @Value("${app.chat-rate-limit.window-seconds:60}") long windowSeconds,
                           @Value("${app.chat-rate-limit.max-users:50000}") int maxUsers) {
        this(perWindow, windowSeconds, maxUsers, Clock.systemUTC());
    }

    ChatRateLimiter(int perWindow, long windowSeconds, int maxUsers, Clock clock) {
        if (perWindow < 1 || windowSeconds < 1 || maxUsers < 1) {
            throw new IllegalArgumentException("Limites do chat devem ser positivos.");
        }
        this.perWindow = perWindow;
        this.windowMillis = windowSeconds * 1000;
        this.maxUsers = maxUsers;
        this.clock = clock;
    }

    public void acquire(UUID userId) {
        long now = clock.millis();

        if (windows.size() >= maxUsers && !windows.containsKey(userId)) {
            windows.entrySet().removeIf(e -> now - e.getValue().start >= windowMillis);
        }

        boolean[] allowed = new boolean[1];
        windows.compute(userId, (k, current) -> {
            Window window = (current == null || now - current.start >= windowMillis) ? new Window(now) : current;
            if (window.count < perWindow) {
                window.count++;
                allowed[0] = true;
            }
            return window;
        });

        if (!allowed[0]) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Você está enviando mensagens muito rápido. Aguarde um instante e tente de novo.");
        }
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }
}