package com.opsnexus.resilience;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalAiResilienceTest {
    @Test
    void transientFailureRetriesOnceAndAuditsActualRetryCount() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ExternalAiResilience resilience = resilience(settings(2, 5, Duration.ofSeconds(1)), db);
        AtomicInteger invocations = new AtomicInteger();

        String result = resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> {
            if (invocations.incrementAndGet() == 1) {
                throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.UNAVAILABLE);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, invocations.get());
        verify(db).update(contains("ai_provider_call_audit"), eq("DEEPSEEK_CHAT"), eq("complete"), eq(true), isNull(), eq(1), anyString(), anyLong());
    }

    @Test
    void authenticationAndBadRequestAreNotRetried() {
        ExternalAiResilience resilience = resilience(settings(2, 5, Duration.ofSeconds(1)), mock(JdbcTemplate.class));
        AtomicInteger authenticationCalls = new AtomicInteger();
        AtomicInteger validationCalls = new AtomicInteger();

        assertThrows(AiProviderException.class, () -> resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> {
            authenticationCalls.incrementAndGet();
            throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.AUTHENTICATION);
        }));
        assertThrows(AiProviderException.class, () -> resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> {
            validationCalls.incrementAndGet();
            throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.BAD_REQUEST);
        }));

        assertEquals(1, authenticationCalls.get());
        assertEquals(1, validationCalls.get());
    }

    @Test
    void timeoutRetriesOnceButStreamingBodyFailureDoesNotRetry() {
        ExternalAiResilience resilience = resilience(settings(2, 5, Duration.ofSeconds(1)), mock(JdbcTemplate.class));
        AtomicInteger timeoutCalls = new AtomicInteger();

        assertThrows(AiProviderException.class, () -> resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> {
            timeoutCalls.incrementAndGet();
            throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.TIMEOUT);
        }));
        assertEquals(2, timeoutCalls.get());

        AiProviderException streamFailure = resilience.recordStreamingFailure(
            AiProvider.DEEPSEEK_CHAT, "stream_body", new java.io.IOException("connection lost after delta"));
        assertEquals(AiProviderFailure.UNAVAILABLE, streamFailure.failure());
        assertEquals("CLOSED", resilience.circuitState(AiProvider.DEEPSEEK_CHAT));
    }

    @Test
    void circuitOpenStopsProviderInvocationsAndHalfOpenSuccessClosesIt() throws Exception {
        ExternalAiResilience resilience = resilience(settings(1, 4, Duration.ofMillis(20)), mock(JdbcTemplate.class));
        AtomicInteger invocations = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            assertThrows(AiProviderException.class, () -> resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> {
                invocations.incrementAndGet();
                throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.UNAVAILABLE);
            }));
        }
        AiProviderException open = assertThrows(AiProviderException.class,
            () -> resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> { invocations.incrementAndGet(); return "unexpected"; }));
        assertEquals(AiProviderFailure.CIRCUIT_OPEN, open.failure());
        assertEquals(4, invocations.get(), "open circuit must reject before invoking the provider");

        Thread.sleep(30);
        assertEquals("recovered", resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> { invocations.incrementAndGet(); return "recovered"; }));
        assertEquals("CLOSED", resilience.circuitState(AiProvider.DEEPSEEK_CHAT));
    }

    @Test
    void chatAndEmbeddingCircuitsAreIndependent() {
        ExternalAiResilience resilience = resilience(settings(1, 2, Duration.ofSeconds(1)), mock(JdbcTemplate.class));
        for (int i = 0; i < 2; i++) {
            assertThrows(AiProviderException.class, () -> resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> {
                throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.UNAVAILABLE);
            }));
        }
        assertEquals("OPEN", resilience.circuitState(AiProvider.DEEPSEEK_CHAT));
        assertEquals("embedding-ok", resilience.execute(AiProvider.DASHSCOPE_EMBEDDING, "embed", () -> "embedding-ok"));
        assertEquals("CLOSED", resilience.circuitState(AiProvider.DASHSCOPE_EMBEDDING));
    }

    @Test
    void providerStatusCategoriesAreStable() {
        ExternalAiResilience resilience = resilience(settings(1, 5, Duration.ofSeconds(1)), mock(JdbcTemplate.class));
        assertEquals(AiProviderFailure.UNAVAILABLE, resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 503).failure());
        assertEquals(AiProviderFailure.RATE_LIMITED, resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 429).failure());
        assertEquals(AiProviderFailure.AUTHENTICATION, resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 401).failure());
        assertEquals(AiProviderFailure.AUTHENTICATION, resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 403).failure());
        assertEquals(AiProviderFailure.BAD_REQUEST, resilience.httpFailure(AiProvider.DASHSCOPE_EMBEDDING, 400).failure());
        assertEquals("AI_PROVIDER_UNAVAILABLE", new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.CIRCUIT_OPEN).errorCode());
    }

    private ExternalAiResilience resilience(AiResilienceProperties.ProviderSettings settings, JdbcTemplate db) {
        AiResilienceProperties properties = new AiResilienceProperties();
        properties.setChat(settings);
        properties.setEmbedding(settings(1, 5, Duration.ofSeconds(1)));
        return new ExternalAiResilience(properties, new AiProviderAuditService(db));
    }

    private AiResilienceProperties.ProviderSettings settings(int attempts, int minimumCalls, Duration openWait) {
        AiResilienceProperties.ProviderSettings settings = new AiResilienceProperties.ProviderSettings();
        settings.setRetryMaxAttempts(attempts);
        settings.setRetryWaitDuration(Duration.ZERO);
        settings.setCircuitSlidingWindowSize(minimumCalls);
        settings.setCircuitMinimumCalls(minimumCalls);
        settings.setCircuitFailureRateThreshold(50);
        settings.setCircuitOpenWaitDuration(openWait);
        settings.setCircuitHalfOpenPermittedCalls(1);
        return settings;
    }
}
