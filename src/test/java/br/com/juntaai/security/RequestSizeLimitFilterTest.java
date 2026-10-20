package br.com.juntaai.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
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
}