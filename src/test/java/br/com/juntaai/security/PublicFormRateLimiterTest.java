package br.com.juntaai.security;

import br.com.juntaai.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicFormRateLimiterTest {
    @Test void expiresWindowsAndKeepsClientsIndependent() {
        Clock clock = mock(Clock.class);
        var now = new AtomicLong(0);
        when(clock.millis()).thenAnswer(inv -> now.get());
        var limiter = new PublicFormRateLimiter(1, 10, 2, 60, clock);
        limiter.acquire("a"); limiter.acquire("b");
        assertLimited(() -> limiter.acquire("a"));
        assertLimited(() -> limiter.acquire("c")); // bounded memory, no active bucket eviction
        now.set(60000);
        limiter.acquire("a"); limiter.acquire("c");
    }

    @Test void globalLimitStopsDistributedClients() {
        var limiter = new PublicFormRateLimiter(5, 2, 10, 60, Clock.systemUTC());
        limiter.acquire("a"); limiter.acquire("b");
        assertLimited(() -> limiter.acquire("c"));
    }

    @Test void concurrentRequestsCannotExceedQuota() {
        var limiter = new PublicFormRateLimiter(5, 100, 100, 60, Clock.systemUTC());
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        IntStream.range(0, 50).parallel().forEach(i -> {
            try { limiter.acquire("a"); accepted.incrementAndGet(); }
            catch (ApiException exception) { assertThat(exception.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS); }
        });
        assertThat(accepted).hasValue(5);
    }

    @Test void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new PublicFormRateLimiter(0, 1, 1, 60, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PublicFormRateLimiter(1, 1, 1, 86401, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertLimited(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }
}
