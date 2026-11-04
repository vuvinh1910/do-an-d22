package com.platform.worker.runner;

import com.platform.worker.model.HttpResponseData;
import com.platform.worker.model.ResolvedHttpRequest;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Bounded retry wrapper for transport failures only.
 * Implements Exponential Backoff to mitigate Thundering Herd / Retry Storm problems.
 * Conforms to RFC 7231 Idempotency: never retries assertion failures and avoids
 * blindly retrying non-idempotent state-mutating requests (e.g. POST).
 */
public final class RetryingHttpRunner implements BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> {

    private static final Set<HttpMethod> IDEMPOTENT_METHODS = Set.of(
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.OPTIONS
    );

    private final BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> delegate;
    private final int maxRetries;
    private final Duration initialBackoff;

    public RetryingHttpRunner(BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> delegate, int maxRetries) {
        this(delegate, maxRetries, Duration.ofMillis(100));
    }

    public RetryingHttpRunner(BiFunction<ResolvedHttpRequest, Duration, Mono<HttpResponseData>> delegate,
                              int maxRetries, Duration initialBackoff) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate runner is required");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff != null ? initialBackoff : Duration.ofMillis(100);
    }

    @Override
    public Mono<HttpResponseData> apply(ResolvedHttpRequest request, Duration timeout) {
        if (request == null || timeout == null) {
            return delegate.apply(request, timeout);
        }

        // Blindly retrying state-mutating requests (e.g. POST) on unknown transport errors can duplicate side effects.
        boolean safeToRetry = IDEMPOTENT_METHODS.contains(request.method()) && maxRetries > 0;
        if (!safeToRetry) {
            return delegate.apply(request, timeout);
        }

        return executeWithRetry(request, timeout, 0);
    }

    private Mono<HttpResponseData> executeWithRetry(ResolvedHttpRequest request, Duration timeout, int attempt) {
        return delegate.apply(request, timeout).flatMap(response -> {
            // Only retry on network transport errors, NEVER on successful HTTP responses or assertion failures.
            if (response.error() != null && attempt < maxRetries) {
                Duration delay = calculateBackoff(attempt);
                if (delay.isZero()) {
                    return executeWithRetry(request, timeout, attempt + 1);
                }
                return Mono.delay(delay).then(Mono.defer(() -> executeWithRetry(request, timeout, attempt + 1)));
            }
            return Mono.just(response);
        }).onErrorResume(RuntimeException.class, error -> {
            if (attempt < maxRetries) {
                Duration delay = calculateBackoff(attempt);
                if (delay.isZero()) {
                    return executeWithRetry(request, timeout, attempt + 1);
                }
                return Mono.delay(delay).then(Mono.defer(() -> executeWithRetry(request, timeout, attempt + 1)));
            }
            return Mono.error(error);
        });
    }

    private Duration calculateBackoff(int attempt) {
        if (initialBackoff.isZero()) {
            return Duration.ZERO;
        }
        long factor = 1L << Math.min(attempt, 30);
        return initialBackoff.multipliedBy(factor);
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }
}
