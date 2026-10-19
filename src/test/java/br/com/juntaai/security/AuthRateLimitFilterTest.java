package br.com.juntaai.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AuthRateLimitFilterTest {

    /** Relógio que dá pra avançar no teste. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MockHttpServletResponse call(AuthRateLimitFilter filter, String method, String uri, String ip,
                                         FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void deveRecusarComTooManyRequestsAposOLimiteEVoltarAPermitirNaProximaJanela() throws Exception {
        MutableClock clock = new MutableClock();
        AuthRateLimitFilter filter = new AuthRateLimitFilter(2, 5, 5, 600, 100, clock);
        FilterChain chain = mock(FilterChain.class);

        assertThat(call(filter, "POST", "/auth/access-code/request", "1.1.1.1", chain).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/auth/access-code/request", "1.1.1.1", chain).getStatus()).isEqualTo(200);

        MockHttpServletResponse blocked = call(filter, "POST", "/auth/access-code/request", "1.1.1.1", chain);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("600");
        verify(chain, times(2)).doFilter(any(), any());

        clock.advanceSeconds(601);
        assertThat(call(filter, "POST", "/auth/access-code/request", "1.1.1.1", chain).getStatus()).isEqualTo(200);
    }

    @Test
    void naoDeveMisturarIpsNemGruposDeRotas() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, 1, 1, 600, 100, new MutableClock());
        FilterChain chain = mock(FilterChain.class);

        assertThat(call(filter, "POST", "/auth/access-code/request", "1.1.1.1", chain).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/auth/access-code/request", "2.2.2.2", chain).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/auth/access-code/verify", "1.1.1.1", chain).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/auth/access-code/request", "1.1.1.1", chain).getStatus()).isEqualTo(429);
    }

    @Test
    void naoDeveLimitarRotasForaDeAuthNemMetodosQueNaoSaoPost() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(1, 1, 1, 600, 100, new MutableClock());
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "GET", "/transactions", "1.1.1.1", chain).getStatus()).isEqualTo(200);
            assertThat(call(filter, "OPTIONS", "/auth/google", "1.1.1.1", chain).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void deveRecusarNovosIpsQuandoATabelaEstaCheia() throws Exception {
        AuthRateLimitFilter filter = new AuthRateLimitFilter(5, 5, 5, 600, 2, new MutableClock());
        FilterChain chain = mock(FilterChain.class);

        assertThat(call(filter, "POST", "/auth/google", "1.1.1.1", chain).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/auth/google", "2.2.2.2", chain).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/auth/google", "3.3.3.3", chain).getStatus()).isEqualTo(429);
        assertThat(call(filter, "POST", "/auth/google", "1.1.1.1", chain).getStatus()).isEqualTo(200);
    }
}