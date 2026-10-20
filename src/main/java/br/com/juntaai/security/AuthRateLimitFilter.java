package br.com.juntaai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limite de requisições por IP nas rotas de autenticação (/auth/**).
 *
 * Protege contra: chute de códigos em massa, spam de e-mails (cada pedido
 * de código manda um e-mail de verdade), e tentativas de sobrecarregar o
 * login. É a primeira barreira; o limite por e-mail e por código fica em
 * EmailAccessCodeService.
 *
 * Janela fixa em memória, por instância da aplicação. Se o Container App
 * rodar com N réplicas, o limite efetivo por IP é até N vezes maior — por
 * isso os limites por e-mail/código (gravados no banco) continuam sendo a
 * garantia principal. Para limite global exato seria preciso Redis/gateway.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private enum Group { CODE_REQUEST, VERIFY, OTHER }

    private final int codeRequestLimit;
    private final int verifyLimit;
    private final int otherLimit;
    private final long windowMillis;
    private final int maxKeys;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(int codeRequestLimit, int verifyLimit, int otherLimit,
                               long windowSeconds, int maxKeys, Clock clock) {
        if (codeRequestLimit < 1 || verifyLimit < 1 || otherLimit < 1 || windowSeconds < 1 || maxKeys < 1) {
            throw new IllegalArgumentException("Limites de autenticação devem ser positivos.");
        }
        this.codeRequestLimit = codeRequestLimit;
        this.verifyLimit = verifyLimit;
        this.otherLimit = otherLimit;
        this.windowMillis = windowSeconds * 1000;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Group group = groupOf(request.getRequestURI());
        String key = group + "|" + request.getRemoteAddr();

        if (!tryAcquire(key, limitOf(group))) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(windowMillis / 1000));
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    ("{\"status\":429,\"erro\":\"Too Many Requests\","
                            + "\"mensagem\":\"Muitas requisições. Aguarde alguns minutos e tente novamente.\"}")
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean tryAcquire(String key, int limit) {
        long now = clock.millis();

        if (windows.size() >= maxKeys && !windows.containsKey(key)) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().start >= windowMillis);
            if (windows.size() >= maxKeys) {
                return false; // tabela cheia de IPs ativos: recusa novos em vez de crescer sem limite
            }
        }

        boolean[] allowed = new boolean[1];
        windows.compute(key, (k, current) -> {
            Window window = (current == null || now - current.start >= windowMillis) ? new Window(now) : current;
            if (window.count < limit) {
                window.count++;
                allowed[0] = true;
            }
            return window;
        });
        return allowed[0];
    }

    private int limitOf(Group group) {
        return switch (group) {
            case CODE_REQUEST -> codeRequestLimit;
            case VERIFY -> verifyLimit;
            case OTHER -> otherLimit;
        };
    }

    private Group groupOf(String uri) {
        if (uri.equals("/auth/register/request-code") || uri.equals("/auth/access-code/request")) {
            return Group.CODE_REQUEST;
        }
        if (uri.equals("/auth/register/verify") || uri.equals("/auth/access-code/verify")) {
            return Group.VERIFY;
        }
        return Group.OTHER;
    }

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }
}