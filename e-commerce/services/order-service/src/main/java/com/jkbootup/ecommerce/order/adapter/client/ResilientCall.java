package com.jkbootup.ecommerce.order.adapter.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Circuit breaker + retry around one downstream service (Gateway pattern). Every call to it shares one breaker, so a
 * failing dependency is cut off quickly and recovers on its own. Retries are only used for calls that are idempotent.
 */
public final class ResilientCall {

    /** What happened when the call could not produce a normal response. */
    public enum Failure { TIMEOUT, CONNECT, CIRCUIT_OPEN, SERVER_ERROR }

    private final CircuitBreaker breaker;
    private final Retry retry;

    public ResilientCall(String name) {
        this.breaker = CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .failureRateThreshold(50).slidingWindowSize(20).minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(10)).permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        this.retry = Retry.of(name, RetryConfig.custom()
                .maxAttempts(3).intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialRandomBackoff(100, 2.0, 0.5))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
    }

    /** Runs the call; when it fails for an infrastructure reason, {@code onFailure} turns that into a domain result. */
    public <T> T run(boolean retryable, Supplier<T> call, Function<Failure, T> onFailure) {
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(breaker, call);
        if (retryable) guarded = Retry.decorateSupplier(retry, guarded);
        try {
            return guarded.get();
        } catch (CallNotPermittedException e) {
            return onFailure.apply(Failure.CIRCUIT_OPEN);
        } catch (ResourceAccessException e) {
            return onFailure.apply(isTimeout(e) ? Failure.TIMEOUT : Failure.CONNECT);
        } catch (HttpServerErrorException e) {
            return onFailure.apply(e.getStatusCode().value() == 504 ? Failure.TIMEOUT : Failure.SERVER_ERROR);
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause())
            if (t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.SocketTimeoutException) return true;
        return false;
    }
}
