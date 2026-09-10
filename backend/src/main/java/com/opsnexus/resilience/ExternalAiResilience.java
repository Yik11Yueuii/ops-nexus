package com.opsnexus.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Applies independent, conservative retry and circuit-breaker policies to each external AI provider.
 * Callers choose an operation boundary: native SSE only retries the pre-token connection handshake.
 */
@Component
public class ExternalAiResilience {
    private final Map<AiProvider, CircuitBreaker> circuitBreakers = new EnumMap<>(AiProvider.class);
    private final Map<AiProvider, Retry> retries = new EnumMap<>(AiProvider.class);
    private final AiProviderAuditService audit;

    public ExternalAiResilience(AiResilienceProperties properties, AiProviderAuditService audit) {
        this.audit = audit;
        for (AiProvider provider : AiProvider.values()) {
            var settings = properties.forProvider(provider);
            circuitBreakers.put(provider, CircuitBreaker.of(provider.instanceName(), circuitConfig(settings)));
            retries.put(provider, Retry.of(provider.instanceName(), retryConfig(settings)));
        }
    }

    public <T> T execute(AiProvider provider, String operation, Supplier<T> call) {
        long started = System.nanoTime();
        AtomicInteger attempts = new AtomicInteger();
        CircuitBreaker breaker = circuitBreakers.get(provider);
        Supplier<T> counted = () -> {
            attempts.incrementAndGet();
            return call.get();
        };
        Supplier<T> circuitProtected = CircuitBreaker.decorateSupplier(breaker, counted);
        try {
            T result = Retry.decorateSupplier(retries.get(provider), circuitProtected).get();
            audit.record(provider, operation, true, null, attempts.get() - 1, breaker.getState().name(), elapsed(started));
            return result;
        } catch (CallNotPermittedException exception) {
            AiProviderException failure = new AiProviderException(provider, AiProviderFailure.CIRCUIT_OPEN, exception);
            audit.record(provider, operation, false, failure.failure(), attempts.get() - 1, breaker.getState().name(), elapsed(started));
            throw failure;
        } catch (Exception exception) {
            AiProviderException failure = classify(provider, exception);
            audit.record(provider, operation, false, failure.failure(), Math.max(0, attempts.get() - 1), breaker.getState().name(), elapsed(started));
            throw failure;
        }
    }

    /** Records a native streaming-body failure without retrying after the caller may have emitted tokens. */
    public AiProviderException recordStreamingFailure(AiProvider provider, String operation, Throwable exception) {
        AiProviderException failure = classify(provider, exception);
        CircuitBreaker breaker = circuitBreakers.get(provider);
        if (failure.failure().circuitFailure()) {
            breaker.onError(0, TimeUnit.NANOSECONDS, failure);
        }
        audit.record(provider, operation, false, failure.failure(), 0, breaker.getState().name(), 0);
        return failure;
    }

    public AiProviderException httpFailure(AiProvider provider, int statusCode) {
        AiProviderFailure failure = switch (statusCode) {
            case 400 -> AiProviderFailure.BAD_REQUEST;
            case 401, 403 -> AiProviderFailure.AUTHENTICATION;
            case 429 -> AiProviderFailure.RATE_LIMITED;
            case 502, 503, 504 -> AiProviderFailure.UNAVAILABLE;
            default -> statusCode >= 500 ? AiProviderFailure.UNAVAILABLE : AiProviderFailure.BAD_REQUEST;
        };
        return new AiProviderException(provider, failure);
    }

    public String circuitState(AiProvider provider) {
        return circuitBreakers.get(provider).getState().name();
    }

    private CircuitBreakerConfig circuitConfig(AiResilienceProperties.ProviderSettings settings) {
        return CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(settings.getCircuitSlidingWindowSize())
            .minimumNumberOfCalls(settings.getCircuitMinimumCalls())
            .failureRateThreshold(settings.getCircuitFailureRateThreshold())
            .waitDurationInOpenState(settings.getCircuitOpenWaitDuration())
            .permittedNumberOfCallsInHalfOpenState(settings.getCircuitHalfOpenPermittedCalls())
            .recordException(error -> error instanceof AiProviderException providerError && providerError.failure().circuitFailure())
            .build();
    }

    private RetryConfig retryConfig(AiResilienceProperties.ProviderSettings settings) {
        return RetryConfig.custom()
            .maxAttempts(settings.getRetryMaxAttempts())
            .waitDuration(settings.getRetryWaitDuration())
            .retryOnException(error -> error instanceof AiProviderException providerError && providerError.failure().retryable())
            .build();
    }

    private AiProviderException classify(AiProvider provider, Throwable exception) {
        if (exception instanceof AiProviderException providerException) {
            return providerException;
        }
        if (exception instanceof HttpTimeoutException) {
            return new AiProviderException(provider, AiProviderFailure.TIMEOUT, exception);
        }
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new AiProviderException(provider, AiProviderFailure.CANCELLED, exception);
        }
        if (exception instanceof IOException) {
            return new AiProviderException(provider, AiProviderFailure.UNAVAILABLE, exception);
        }
        return new AiProviderException(provider, AiProviderFailure.UNAVAILABLE, exception);
    }

    private long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
