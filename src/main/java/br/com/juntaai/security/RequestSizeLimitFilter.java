package br.com.juntaai.security;

import br.com.juntaai.exception.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Preserves the existing global Content-Length check; bounds actual reads only for public forms. */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final int PUBLIC_FORM_MAX_BYTES = 65536;
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
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        boolean publicForm = "POST".equals(request.getMethod())
                && ("/contact".equals(path) || "/feedback".equals(path));
        long limit = publicForm ? Math.min(maxBytes, PUBLIC_FORM_MAX_BYTES) : maxBytes;
        if (request.getContentLengthLong() > limit) {
            response.setStatus(413);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(
                    ("{\"status\":413,\"erro\":\"Payload Too Large\",\"mensagem\":\"Requisição grande demais.\"}")
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        chain.doFilter(publicForm ? new BoundedFormRequest(request, (int) limit) : request, response);
    }

    private static final class BoundedFormRequest extends HttpServletRequestWrapper {
        private final int limit;
        private ServletInputStream body;

        BoundedFormRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (body == null) {
                // Lazy: MVC rate limiting runs before this read. At most 64 KiB + one byte,
                // including unknown JSON fields and trailing bytes, before any SMTP processing.
                byte[] bytes = super.getInputStream().readNBytes(limit + 1);
                if (bytes.length > limit) {
                    throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Requisição grande demais.");
                }
                var input = new ByteArrayInputStream(bytes);
                body = new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) {
                        throw new UnsupportedOperationException("Public forms use synchronous request processing.");
                    }
                };
            }
            return body;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
