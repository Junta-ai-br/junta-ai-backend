package br.com.juntaai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Recusa corpos de requisição muito grandes (413) antes de o Jackson tentar
 * ler. Todas as nossas requisições são JSON pequeno (a maior é uma mensagem
 * de chat de até 2000 caracteres). Sem este filtro, qualquer pessoa poderia
 * mandar dezenas de MB para uma rota pública e consumir memória do servidor.
 *
 * Limite: confere o cabeçalho Content-Length. Requisições "chunked" (sem
 * Content-Length) não são cobertas aqui; o Tomcat e o gateway do Azure
 * já limitam esse caso.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;

    public RequestSizeLimitFilter(long maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("Limite de tamanho deve ser positivo.");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(413);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    ("{\"status\":413,\"erro\":\"Payload Too Large\",\"mensagem\":\"Requisição grande demais.\"}")
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(request, response);
    }
}