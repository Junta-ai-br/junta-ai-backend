package br.com.juntaai.security;

import br.com.juntaai.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRateLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void deveBloquearAcimaDoLimiteSoParaAqueleUsuarioELiberarNaProximaJanela() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(2, 60, 100, clock);
        UUID ana = UUID.randomUUID();
        UUID bia = UUID.randomUUID();

        limiter.acquire(ana);
        limiter.acquire(ana);
        assertThatThrownBy(() -> limiter.acquire(ana)).isInstanceOf(ApiException.class);

        assertThatCode(() -> limiter.acquire(bia)).doesNotThrowAnyException();

        clock.advanceSeconds(61);
        assertThatCode(() -> limiter.acquire(ana)).doesNotThrowAnyException();
    }
}