package com.platform.worker.runner;

import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryingHttpRunnerTest {
    private final Duration timeout = Duration.ofSeconds(2);
    private final URI uri = URI.create("http://localhost:8080/test");

    @Test
    void rejectsNegativeMaxRetries() {
        assertThrows(IllegalArgumentException.class, () -> new RetryingHttpRunner((req, t) -> Mono.empty(), -1));
    }

    @Test
    void noRetryWhenMaxRetriesIsZero() {
        var attempts = new AtomicInteger();
        var req = new ResolvedHttpRequest(HttpMethod.GET, uri, Map.of(), null);
        var runner = new RetryingHttpRunner((r, t) -> {
            attempts.incrementAndGet();
            return Mono.just(new HttpResponseData(null, Map.of(), null, 10, "Connection refused"));
        }, 0, Duration.ZERO);

        var response = runner.apply(req, timeout).block(timeout);
        assertNotNull(response);
        assertEquals(1, attempts.get());
        assertEquals("Connection refused", response.error());
    }

    @Test
    void retriesTransientTransportErrorAndSucceeds() {
        var attempts = new AtomicInteger();
        var req = new ResolvedHttpRequest(HttpMethod.GET, uri, Map.of(), null);
        var runner = new RetryingHttpRunner((r, t) -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                return Mono.just(new HttpResponseData(null, Map.of(), null, 15, "Timeout"));
            }
            return Mono.just(new HttpResponseData(200, Map.of(), "{\"status\":\"ok\"}", 25, null));
        }, 2, Duration.ZERO);

        var response = runner.apply(req, timeout).block(timeout);
        assertNotNull(response);
        assertEquals(2, attempts.get());
        assertNull(response.error());
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"ok\"}", response.body());
    }

    @Test
    void retriesUntilExhaustionWhenTransportErrorPersists() {
        var attempts = new AtomicInteger();
        var req = new ResolvedHttpRequest(HttpMethod.GET, uri, Map.of(), null);
        var runner = new RetryingHttpRunner((r, t) -> {
            attempts.incrementAndGet();
            return Mono.just(new HttpResponseData(null, Map.of(), null, 10, "Network down"));
        }, 2, Duration.ZERO); // 1 initial + 2 retries = 3 attempts

        var response = runner.apply(req, timeout).block(timeout);
        assertNotNull(response);
        assertEquals(3, attempts.get());
        assertEquals("Network down", response.error());
    }

    @Test
    void doesNotRetryAssertionOrHttpErrorResponses() {
        var attempts = new AtomicInteger();
        var req = new ResolvedHttpRequest(HttpMethod.GET, uri, Map.of(), null);
        var runner = new RetryingHttpRunner((r, t) -> {
            attempts.incrementAndGet();
            // HTTP 400 Bad Request with response body (not a network transport drop)
            return Mono.just(new HttpResponseData(400, Map.of(), "{\"error\":\"bad input\"}", 20, null));
        }, 3, Duration.ZERO);

        var response = runner.apply(req, timeout).block(timeout);
        assertNotNull(response);
        assertEquals(1, attempts.get());
        assertEquals(400, response.statusCode());
    }

    @Test
    void doesNotBlindlyRetryNonIdempotentPostRequests() {
        var attempts = new AtomicInteger();
        var req = new ResolvedHttpRequest(HttpMethod.POST, uri, Map.of(), null);
        var runner = new RetryingHttpRunner((r, t) -> {
            attempts.incrementAndGet();
            return Mono.just(new HttpResponseData(null, Map.of(), null, 10, "Premature connection close"));
        }, 3, Duration.ZERO);

        var response = runner.apply(req, timeout).block(timeout);
        assertNotNull(response);
        assertEquals(1, attempts.get(), "POST requests must not be blindly retried on transport errors");
        assertEquals("Premature connection close", response.error());
    }

    @Test
    void exponentialBackoffCalculatesProperDelays() {
        var runner = new RetryingHttpRunner((r, t) -> Mono.empty(), 3, Duration.ofMillis(50));
        assertEquals(Duration.ofMillis(50), runner.getInitialBackoff());

        long start = System.currentTimeMillis();
        var attempts = new AtomicInteger();
        var req = new ResolvedHttpRequest(HttpMethod.GET, uri, Map.of(), null);
        var backoffRunner = new RetryingHttpRunner((r, t) -> {
            int a = attempts.incrementAndGet();
            if (a < 2) {
                return Mono.just(new HttpResponseData(null, Map.of(), null, 5, "Connection reset"));
            }
            return Mono.just(new HttpResponseData(200, Map.of(), "{}", 10, null));
        }, 2, Duration.ofMillis(30));

        var res = backoffRunner.apply(req, timeout).block(timeout);
        long elapsed = System.currentTimeMillis() - start;
        assertNotNull(res);
        assertEquals(200, res.statusCode());
        assertTrue(elapsed >= 25, "Exponential backoff should introduce at least initialBackoff delay: " + elapsed + "ms");
    }
}
