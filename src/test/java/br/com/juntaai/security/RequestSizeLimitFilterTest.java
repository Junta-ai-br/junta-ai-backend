package br.com.juntaai.security;

import jakarta.servlet.FilterChain;
import br.com.juntaai.exception.ApiException;
import java.io.ByteArrayInputStream;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class RequestSizeLimitFilterTest {

    @Test
    void deveRecusarCorpoMaiorQueOLimiteESeguirCorpoMenor() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(100);
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest grande = new MockHttpServletRequest("POST", "/contact");
        grande.setContent(new byte[101]);
        MockHttpServletResponse recusada = new MockHttpServletResponse();
        filter.doFilter(grande, recusada, chain);

        assertThat(recusada.getStatus()).isEqualTo(413);
        verify(chain, never()).doFilter(any(), any());

        MockHttpServletRequest pequeno = new MockHttpServletRequest("POST", "/contact");
        pequeno.setContent(new byte[100]);
        MockHttpServletResponse aceita = new MockHttpServletResponse();
        filter.doFilter(pequeno, aceita, chain);

        assertThat(aceita.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test void boundsUnknownLengthReadsAndDoesNotBufferTheWholeExcess() throws Exception {
        var bytes = new ByteArrayInputStream(new byte[1024]);
        var request = new MockHttpServletRequest("POST", "/contact") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public jakarta.servlet.ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(bytes);
            }
        };
        var filter = new RequestSizeLimitFilter(100);
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (wrapped, response) -> wrapped.getInputStream().readAllBytes()))
                .isInstanceOf(ApiException.class).satisfies(ex ->
                        assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(bytes.available()).isEqualTo(1024 - 101);
    }

    @Test void leavesUnknownLengthBodiesOfOtherEndpointsUnwrapped() throws Exception {
        var request = new MockHttpServletRequest("POST", "/auth/access-code/request");
        new RequestSizeLimitFilter(100).doFilter(request, new MockHttpServletResponse(),
                (wrapped, response) -> assertThat(wrapped).isSameAs(request));
    }
}
