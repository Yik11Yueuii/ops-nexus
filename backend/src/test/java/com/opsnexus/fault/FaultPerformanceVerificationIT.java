package com.opsnexus.fault;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.analytics.SqlSafetyValidator;
import com.opsnexus.assistant.ConversationContextService;
import com.opsnexus.assistant.DeepSeekChat;
import com.opsnexus.assistant.DiagnosisToolAuditService;
import com.opsnexus.assistant.DiagnosisToolFunctions;
import com.opsnexus.assistant.DiagnosisToolRegistry;
import com.opsnexus.knowledge.KnowledgeException;
import com.opsnexus.observability.OpsNexusMetrics;
import com.opsnexus.observability.RedisFallbackHealthIndicator;
import com.opsnexus.observability.TraceContext;
import com.opsnexus.resilience.AiProvider;
import com.opsnexus.resilience.AiProviderAuditService;
import com.opsnexus.resilience.AiProviderException;
import com.opsnexus.resilience.AiProviderFailure;
import com.opsnexus.resilience.AiResilienceProperties;
import com.opsnexus.resilience.ExternalAiResilience;
import com.opsnexus.security.PromptTrustBoundary;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Opt-in local-only verification; dependencies are mocks or in-process test servers. */
class FaultPerformanceVerificationIT {
    private static final int WARMUP_REQUESTS = 20, MEASURED_REQUESTS = 200, CONCURRENCY = 10;

    @Test
    void retriesTransient503OnceAndAuditsTheExactAttemptCount() {
        Fixture fixture = fixture(2, 4); AtomicInteger invocations = new AtomicInteger();
        String result = fixture.resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> {
            if (invocations.incrementAndGet() == 1) throw fixture.resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 503);
            return "recovered";
        });
        assertEquals("recovered", result); assertEquals(2, invocations.get());
        assertFalse(fixture.registry.find("resilience4j.retry.calls").meters().isEmpty());
        assertEquals(1.0, fixture.registry.get("opsnexus.ai.calls").tag("provider", "DEEPSEEK_CHAT").tag("outcome", "SUCCESS").counter().count());
        verify(fixture.auditDb).update(contains("ai_provider_call_audit"), any(), any(), any(), any(), any(), any(), any(), any());
        System.out.println("fault transient-503 requests=1 providerInvocations=2 outcome=SUCCESS retry=1");
    }

    @Test
    void opensCircuitAndRejectsConcurrentPersistentFailuresWithoutExtraProviderCalls() throws Exception {
        Fixture fixture = fixture(1, 4); AtomicInteger invocations = new AtomicInteger();
        Supplier<String> unavailable = () -> { invocations.incrementAndGet(); throw fixture.resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 503); };
        for (int index = 0; index < 4; index++) assertEquals(AiProviderFailure.UNAVAILABLE, assertThrows(AiProviderException.class,
            () -> fixture.resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", unavailable)).failure());
        assertEquals("OPEN", fixture.resilience.circuitState(AiProvider.DEEPSEEK_CHAT));
        int callsBeforeLoad = invocations.get(); long started = System.nanoTime();
        List<String> failures = concurrently(40, 10, () -> assertThrows(AiProviderException.class,
            () -> fixture.resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", unavailable)).failure().name());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(failures.stream().allMatch("CIRCUIT_OPEN"::equals)); assertEquals(callsBeforeLoad, invocations.get()); assertTrue(elapsedMs < 1_000);
        System.out.printf(Locale.ROOT, "fault persistent-503 requests=44 providerInvocations=%d concurrentRejected=40 circuit=OPEN elapsedMs=%d%n", invocations.get(), elapsedMs);
    }

    @Test
    void classifiesNonRetryableAndRateLimitedProviderResponsesSafely() {
        Fixture fixture = fixture(2, 4);
        for (int status : List.of(400, 401, 403)) {
            AtomicInteger invocations = new AtomicInteger(); AiProviderException failure = assertThrows(AiProviderException.class, () -> fixture.resilience.execute(
                AiProvider.DEEPSEEK_CHAT, "complete", () -> { invocations.incrementAndGet(); throw fixture.resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, status); }));
            assertEquals(1, invocations.get()); assertEquals(status == 400 ? AiProviderFailure.BAD_REQUEST : AiProviderFailure.AUTHENTICATION, failure.failure());
        }
        AtomicInteger malformedCalls = new AtomicInteger(); AiProviderException malformed = assertThrows(AiProviderException.class, () -> fixture.resilience.execute(
            AiProvider.DEEPSEEK_CHAT, "complete", () -> { malformedCalls.incrementAndGet(); throw new AiProviderException(AiProvider.DEEPSEEK_CHAT, AiProviderFailure.MALFORMED_RESPONSE); }));
        assertEquals(AiProviderFailure.MALFORMED_RESPONSE, malformed.failure()); assertEquals(1, malformedCalls.get());
        AtomicInteger rateLimitedCalls = new AtomicInteger(); AiProviderException rateLimited = assertThrows(AiProviderException.class, () -> fixture.resilience.execute(
            AiProvider.DEEPSEEK_CHAT, "complete", () -> { rateLimitedCalls.incrementAndGet(); throw fixture.resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 429); }));
        assertEquals(AiProviderFailure.RATE_LIMITED, rateLimited.failure()); assertEquals(2, rateLimitedCalls.get());
        System.out.println("fault non-retryable=400,401,403,malformed invocations=1; rate-limit-429 invocations=2");
    }

    @Test
    void retriesOnlyTheSseHandshakeAndNeverReplaysAnEmittedDelta() throws Exception {
        Fixture fixture = fixture(2, 4); AtomicInteger handshakeCalls = new AtomicInteger(), emittedCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/handshake", exchange -> { if (handshakeCalls.incrementAndGet() == 1) exchange.sendResponseHeaders(503, -1); else sse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"recovered\"}}]}\n\ndata: [DONE]\n\n"); exchange.close(); });
        server.createContext("/after-delta", exchange -> { emittedCalls.incrementAndGet(); sse(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\ndata: {not-json}\n\n"); exchange.close(); });
        server.start();
        try {
            List<String> recovered = new ArrayList<>(); TraceContext.set("fault-013-trace");
            chat(server, "/handshake", fixture.resilience, fixture.properties).stream("system", "question", recovered::add);
            assertEquals(List.of("recovered"), recovered); assertEquals(2, handshakeCalls.get()); assertEquals("fault-013-trace", TraceContext.current());
            List<String> output = new ArrayList<>(); AiProviderException failure = assertThrows(AiProviderException.class,
                () -> chat(server, "/after-delta", fixture.resilience, fixture.properties).stream("system", "question", output::add));
            assertEquals(AiProviderFailure.MALFORMED_RESPONSE, failure.failure()); assertEquals(List.of("first"), output); assertEquals(1, emittedCalls.get());
        } finally { TraceContext.clear(); server.stop(0); }
        System.out.println("fault sse pre-token=2 invocations recovered; post-token=1 invocation no-duplicate-delta stable=MALFORMED_RESPONSE");
    }

    @Test
    void keepsChatAndEmbeddingCircuitsIndependent() {
        Fixture fixture = fixture(1, 4);
        for (int index = 0; index < 4; index++) assertThrows(AiProviderException.class, () -> fixture.resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> { throw fixture.resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 503); }));
        assertEquals("OPEN", fixture.resilience.circuitState(AiProvider.DEEPSEEK_CHAT)); assertEquals("CLOSED", fixture.resilience.circuitState(AiProvider.DASHSCOPE_EMBEDDING));
        assertEquals("vector", fixture.resilience.execute(AiProvider.DASHSCOPE_EMBEDDING, "embed", () -> "vector"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackFromMockedRedisWithoutCrossingUserOrConversationBoundaries() {
        JdbcTemplate db = mock(JdbcTemplate.class); StringRedisTemplate redis = mock(StringRedisTemplate.class); ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values); when(values.get(anyString())).thenThrow(new IllegalStateException("fake redis outage"));
        when(db.queryForList(anyString(), eq(33L), eq(7L), eq(2))).thenReturn(List.of(Map.of("ROLE", "ASSISTANT", "CONTENT", "scoped answer"), Map.of("ROLE", "USER", "CONTENT", "scoped request")));
        ConversationContextService.Snapshot result = new ConversationContextService(db, redis, new ObjectMapper(), 2, 200, 60).recent(7, 33);
        assertEquals("DATABASE", result.source()); assertEquals(List.of("scoped request", "scoped answer"), result.messages().stream().map(ConversationContextService.Message::content).toList());
        verify(db).queryForList(anyString(), eq(33L), eq(7L), eq(2)); when(redis.getConnectionFactory()).thenThrow(new IllegalStateException("fake redis outage"));
        assertEquals(new Status("DEGRADED"), new RedisFallbackHealthIndicator(redis).health().getStatus());
        System.out.println("fault redis-fallback source=DATABASE user=7 conversation=33 health=DEGRADED/DATABASE_FALLBACK");
    }

    @Test
    void rejectsSqlAttackShapesBeforeAnyDatabaseExecutionAndClampsLimit() {
        SqlSafetyValidator validator = new SqlSafetyValidator();
        assertSqlFailure(validator, "DELETE FROM service_catalog", "SQL_STATEMENT_NOT_ALLOWED"); assertSqlFailure(validator, "SELECT id FROM service_catalog UNION SELECT id FROM release_record", "SQL_STATEMENT_NOT_ALLOWED");
        assertSqlFailure(validator, "SELECT id FROM user_account", "SQL_TABLE_NOT_ALLOWED"); assertSqlFailure(validator, "SELECT password FROM service_catalog", "SQL_COLUMN_NOT_ALLOWED");
        assertSqlFailure(validator, "SELECT SLEEP(1) FROM service_catalog", "SQL_FUNCTION_NOT_ALLOWED"); assertSqlFailure(validator, "SELECT id FROM service_catalog CROSS JOIN release_record", "SQL_JOIN_NOT_ALLOWED");
        assertSqlFailure(validator, "SELECT id FROM service_catalog WHERE id IN (SELECT id FROM release_record)", "SQL_SUBQUERY_NOT_ALLOWED");
        assertTrue(validator.validate("SELECT id, service_name FROM service_catalog LIMIT 9999").contains("LIMIT 100"));
        System.out.println("fault sql attacks=7 rejected before execution; excessive-limit=9999 clamped=100");
    }

    @Test
    @SuppressWarnings("unchecked")
    void boundsToolThrowAndTimeoutWithStableAuditAndMetricCategories() {
        JdbcTemplate throwingDb = mock(JdbcTemplate.class); JdbcTemplate auditDb = mock(JdbcTemplate.class);
        when(throwingDb.queryForObject(contains("COUNT"), eq(Integer.class), eq("order-service"))).thenReturn(1);
        when(throwingDb.queryForObject(startsWith("SELECT service_name"), any(RowMapper.class), eq("order-service"))).thenThrow(new IllegalStateException("fake tool failure"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DiagnosisToolRegistry throwing = toolRegistry(throwingDb, auditDb, registry);
        KnowledgeException thrown = assertThrows(KnowledgeException.class, () -> throwing.execute(7, true, "fault-throw", statusRequest()));
        assertEquals("TOOL_EXECUTION_FAILED", thrown.code);
        verify(auditDb).update(contains("diagnosis_tool_call_audit"), any(), any(), any(), any(), any(), any(), eq("TOOL_EXECUTION_FAILED"), any(), any(), any());

        JdbcTemplate blockingDb = mock(JdbcTemplate.class); JdbcTemplate timeoutAuditDb = mock(JdbcTemplate.class); CountDownLatch blocked = new CountDownLatch(1);
        when(blockingDb.queryForObject(contains("COUNT"), eq(Integer.class), eq("order-service"))).thenReturn(1);
        when(blockingDb.queryForObject(startsWith("SELECT service_name"), any(RowMapper.class), eq("order-service"))).thenAnswer(invocation -> {
            try { blocked.await(); return null; } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        });
        DiagnosisToolRegistry timeout = toolRegistry(blockingDb, timeoutAuditDb, new SimpleMeterRegistry());
        KnowledgeException timedOut = assertThrows(KnowledgeException.class, () -> timeout.execute(7, true, "fault-timeout", statusRequest()));
        assertEquals("TOOL_TIMEOUT", timedOut.code); verify(timeoutAuditDb).update(contains("diagnosis_tool_call_audit"), any(), any(), any(), any(), any(), any(), eq("TOOL_TIMEOUT"), any(), any(), any());
        KnowledgeException denied = assertThrows(KnowledgeException.class, () -> timeout.execute(7, false, "fault-denied", new DiagnosisToolRegistry.ToolRequest("fault", "lookup_recent_incidents", "{\"input\":{\"service\":\"order-service\"}}")));
        assertEquals("TOOL_ACCESS_DENIED", denied.code);
        System.out.println("fault tool throw=TOOL_EXECUTION_FAILED timeout=TOOL_TIMEOUT unauthorized=TOOL_ACCESS_DENIED");
    }

    @Test
    void printsARepeatableComponentPerformanceBaselineWithoutNetworkOrSecrets() throws Exception {
        Fixture chatFixture = fixture(1, 100);
        PerformanceResult chat = measure("rag-chat-equivalent", () -> { fixedRetrieval(); return chatFixture.resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> "fixed-answer") != null; });
        PerformanceResult tool = measure("tool-diagnosis-equivalent", () -> deterministicTool("order-service") != null);
        PerformanceResult semantic = measure("semantic-version-compare-equivalent", () -> lcs("abcde-service-v1", "axcye-service-v2") > 0);
        assertEquals(0, chat.errors + tool.errors + semantic.errors);
        Fixture faultFixture = fixture(1, 4); AtomicInteger calls = new AtomicInteger();
        for (int index = 0; index < 4; index++) assertThrows(AiProviderException.class, () -> faultFixture.resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> { calls.incrementAndGet(); throw faultFixture.resilience.httpFailure(AiProvider.DEEPSEEK_CHAT, 503); }));
        PerformanceResult rejected = measure("open-circuit-fault-load", () -> { try { faultFixture.resilience.execute(AiProvider.DEEPSEEK_CHAT, "complete", () -> { calls.incrementAndGet(); return "unexpected"; }); return false; } catch (AiProviderException exception) { return exception.failure() == AiProviderFailure.CIRCUIT_OPEN; } });
        assertEquals(4, calls.get()); print(chat); print(tool); print(semantic); print(rejected);
    }

    private static Fixture fixture(int retryAttempts, int minimumCalls) {
        AiResilienceProperties properties = new AiResilienceProperties();
        for (AiResilienceProperties.ProviderSettings settings : List.of(properties.getChat(), properties.getEmbedding())) { settings.setRetryMaxAttempts(retryAttempts); settings.setRetryWaitDuration(Duration.ZERO); settings.setCircuitMinimumCalls(minimumCalls); settings.setCircuitSlidingWindowSize(Math.max(4, minimumCalls)); settings.setCircuitFailureRateThreshold(50); settings.setCircuitOpenWaitDuration(Duration.ofSeconds(30)); }
        JdbcTemplate auditDb = mock(JdbcTemplate.class); MeterRegistry registry = new SimpleMeterRegistry();
        return new Fixture(properties, new ExternalAiResilience(properties, new AiProviderAuditService(auditDb), registry, new OpsNexusMetrics(registry)), registry, auditDb);
    }
    private static DeepSeekChat chat(HttpServer server, String path, ExternalAiResilience resilience, AiResilienceProperties properties) { return new DeepSeekChat("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + path, "fake-model", new ObjectMapper(), properties, resilience, new PromptTrustBoundary()); }
    private static void sse(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
    private static void assertSqlFailure(SqlSafetyValidator validator, String sql, String code) { assertEquals(code, assertThrows(KnowledgeException.class, () -> validator.validate(sql)).code); }
    private static DiagnosisToolRegistry toolRegistry(JdbcTemplate toolDb, JdbcTemplate auditDb, MeterRegistry registry) { return new DiagnosisToolRegistry(new ObjectMapper(), new DiagnosisToolFunctions(toolDb), new DiagnosisToolAuditService(auditDb), new PromptTrustBoundary(), new OpsNexusMetrics(registry)); }
    private static DiagnosisToolRegistry.ToolRequest statusRequest() { return new DiagnosisToolRegistry.ToolRequest("fault", "lookup_service_status", "{\"input\":{\"service\":\"order-service\"}}"); }
    private static <T> List<T> concurrently(int requests, int concurrency, Callable<T> task) throws Exception { ExecutorService workers = Executors.newFixedThreadPool(concurrency); try { CountDownLatch ready = new CountDownLatch(concurrency), start = new CountDownLatch(1); List<java.util.concurrent.Future<T>> futures = new ArrayList<>(); for (int index = 0; index < requests; index++) futures.add(workers.submit(() -> { ready.countDown(); start.await(2, TimeUnit.SECONDS); return task.call(); })); assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown(); List<T> results = new ArrayList<>(); for (var future : futures) results.add(future.get(5, TimeUnit.SECONDS)); return results; } finally { workers.shutdownNow(); } }
    private static PerformanceResult measure(String name, Supplier<Boolean> operation) throws Exception { for (int index = 0; index < WARMUP_REQUESTS; index++) assertTrue(operation.get()); List<Long> timings = Collections.synchronizedList(new ArrayList<>()); AtomicInteger errors = new AtomicInteger(); long started = System.nanoTime(); concurrently(MEASURED_REQUESTS, CONCURRENCY, () -> { long requestStarted = System.nanoTime(); if (!operation.get()) errors.incrementAndGet(); timings.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - requestStarted)); return null; }); long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started); List<Long> sorted = timings.stream().sorted().toList(); return new PerformanceResult(name, MEASURED_REQUESTS, CONCURRENCY, WARMUP_REQUESTS, percentile(sorted, .50), percentile(sorted, .95), percentile(sorted, .99), MEASURED_REQUESTS * 1_000_000.0 / elapsedMicros, errors.get()); }
    private static long percentile(List<Long> values, double quantile) { return values.get(Math.min(values.size() - 1, Math.max(0, (int)Math.ceil(values.size() * quantile) - 1))); }
    private static void print(PerformanceResult result) { System.out.printf(Locale.ROOT, "performance scenario=%s warmup=%d requests=%d concurrency=%d throughput=%.1f req/s p50=%dus p95=%dus p99=%dus errors=%d%n", result.name, result.warmup, result.requests, result.concurrency, result.throughput, result.p50Micros, result.p95Micros, result.p99Micros, result.errors); }
    private static void fixedRetrieval() { for (String ignored : List.of("release", "incident", "runbook", "owner")) { } }
    private static String deterministicTool(String service) { return Map.of("order-service", "HEALTHY", "payment-service", "DEGRADED").get(service); }
    private static int lcs(String left, String right) { int[][] lengths = new int[left.length() + 1][right.length() + 1]; for (int i = 1; i <= left.length(); i++) for (int j = 1; j <= right.length(); j++) lengths[i][j] = left.charAt(i - 1) == right.charAt(j - 1) ? lengths[i - 1][j - 1] + 1 : Math.max(lengths[i - 1][j], lengths[i][j - 1]); return lengths[left.length()][right.length()]; }
    private record Fixture(AiResilienceProperties properties, ExternalAiResilience resilience, MeterRegistry registry, JdbcTemplate auditDb) { }
    private record PerformanceResult(String name, int requests, int concurrency, int warmup, long p50Micros, long p95Micros, long p99Micros, double throughput, int errors) { }
}
