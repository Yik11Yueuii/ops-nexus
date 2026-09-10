package com.opsnexus.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import com.opsnexus.observability.OpsNexusMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final OpsNexusMetrics metrics;
    private final Map<AiProvider, AtomicReference<Boolean>> recentOutcome = new EnumMap<>(AiProvider.class);

    @Autowired
    public ExternalAiResilience(AiResilienceProperties properties, AiProviderAuditService audit, MeterRegistry registry, OpsNexusMetrics metrics) {
        this.audit = audit;
        this.metrics = metrics;
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        RetryRegistry retryRegistry = RetryRegistry.ofDefaults();
        for (AiProvider provider : AiProvider.values()) {
            var settings = properties.forProvider(provider);
            circuitBreakers.put(provider, breakers.circuitBreaker(provider.instanceName(), circuitConfig(settings)));
            retries.put(provider, retryRegistry.retry(provider.instanceName(), retryConfig(settings)));
            recentOutcome.put(provider, new AtomicReference<>());
        }
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(registry);
    }

    /** Keeps focused resilience unit tests independent from a Spring context. */
    public ExternalAiResilience(AiResilienceProperties properties, AiProviderAuditService audit) {
        this(properties, audit, new SimpleMeterRegistry(), new OpsNexusMetrics(new SimpleMeterRegistry()));
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
            record(provider, operation, true, null, attempts.get() - 1, breaker.getState().name(), elapsed(started));
            return result;
        } catch (CallNotPermittedException exception) {
            AiProviderException failure = new AiProviderException(provider, AiProviderFailure.CIRCUIT_OPEN, exception);
            record(provider, operation, false, failure.failure(), attempts.get() - 1, breaker.getState().name(), elapsed(started));
            throw failure;
        } catch (Exception exception) {
            AiProviderException failure = classify(provider, exception);
            record(provider, operation, false, failure.failure(), Math.max(0, attempts.get() - 1), breaker.getState().name(), elapsed(started));
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
        record(provider, operation, false, failure.failure(), 0, breaker.getState().name(), 0);
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

    public boolean hasRecentOutcome(AiProvider provider) { return recentOutcome.get(provider).get() != null; }
    public boolean recentSuccess(AiProvider provider) { return Boolean.TRUE.equals(recentOutcome.get(provider).get()); }

    private void record(AiProvider provider, String operation, boolean success, AiProviderFailure failure, int retryCount, String state, long latencyMs) {
        audit.record(provider, operation, success, failure, Math.max(0, retryCount), state, latencyMs);
        metrics.ai(provider.name(), operation, success, failure == null ? null : failure.name(), latencyMs);
        recentOutcome.get(provider).set(success);
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
