package com.opsnexus.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Centralizes metric names and bounds every business tag to a small, documented vocabulary. */
@Component
public class OpsNexusMetrics {
    private static final Set<String> DIAGNOSIS_TOOLS = Set.of("lookup_service_status", "lookup_recent_releases", "lookup_recent_incidents");
    private final MeterRegistry registry;
    public OpsNexusMetrics(MeterRegistry registry) { this.registry = registry; }
    public void ai(String provider, String operation, boolean success, String failure, long elapsedMs) {
        String outcome = success ? "SUCCESS" : "FAILURE";
        String category = success ? "NONE" : bounded(failure);
        Counter.builder("opsnexus.ai.calls").tags("provider", bounded(provider), "operation", aiOperation(operation), "outcome", outcome, "failure_category", category).register(registry).increment();
        Timer.builder("opsnexus.ai.latency").tags("provider", bounded(provider), "operation", aiOperation(operation), "outcome", outcome).register(registry).record(java.time.Duration.ofMillis(Math.max(0, elapsedMs)));
    }
    public void rag(int candidates, int hits, String outcome, long elapsedMs) {
        String safe = bounded(outcome);
        Counter.builder("opsnexus.rag.requests").tags("outcome", safe).register(registry).increment();
        if (hits == 0) Counter.builder("opsnexus.rag.no_hits").tags("outcome", safe).register(registry).increment();
        DistributionSummary.builder("opsnexus.rag.candidates").tags("outcome", safe).register(registry).record(Math.max(0, candidates));
        DistributionSummary.builder("opsnexus.rag.hits").tags("outcome", safe).register(registry).record(Math.max(0, hits));
        Timer.builder("opsnexus.rag.latency").tags("outcome", safe).register(registry).record(java.time.Duration.ofMillis(Math.max(0, elapsedMs)));
    }
    public void tool(String tool, String event, boolean success, String failure, long elapsedMs) {
        String name = DIAGNOSIS_TOOLS.contains(tool) ? tool : "UNKNOWN";
        Counter.builder("opsnexus.tool.calls").tags("tool", name, "event", bounded(event), "outcome", success ? "SUCCESS" : "FAILURE", "failure_category", success ? "NONE" : bounded(failure)).register(registry).increment();
        if (elapsedMs >= 0) Timer.builder("opsnexus.tool.latency").tags("tool", name, "outcome", success ? "SUCCESS" : "FAILURE").register(registry).record(java.time.Duration.ofMillis(elapsedMs));
    }
    public void sql(String event, boolean success, String failure, long elapsedMs) {
        Counter.builder("opsnexus.sql.queries").tags("event", bounded(event), "outcome", success ? "SUCCESS" : "FAILURE", "failure_category", success ? "NONE" : bounded(failure)).register(registry).increment();
        if (elapsedMs >= 0) Timer.builder("opsnexus.sql.latency").tags("outcome", success ? "SUCCESS" : "FAILURE").register(registry).record(java.time.Duration.ofMillis(elapsedMs));
    }
    public void semantic(String outcome, String failure, long elapsedMs) {
        Counter.builder("opsnexus.semantic.comparisons").tags("outcome", bounded(outcome), "failure_category", failure == null ? "NONE" : bounded(failure)).register(registry).increment();
        Timer.builder("opsnexus.semantic.latency").tags("outcome", bounded(outcome)).register(registry).record(java.time.Duration.ofMillis(Math.max(0, elapsedMs)));
    }
    public void ingestion(String event, boolean success, long elapsedMs) {
        Counter.builder("opsnexus.ingestion.events").tags("event", bounded(event), "outcome", success ? "SUCCESS" : "FAILURE").register(registry).increment();
        if (elapsedMs >= 0) Timer.builder("opsnexus.ingestion.duration").tags("outcome", success ? "SUCCESS" : "FAILURE").register(registry).record(java.time.Duration.ofMillis(elapsedMs));
    }
    private String aiOperation(String operation) {
        return switch (operation == null ? "" : operation.toUpperCase(Locale.ROOT)) {
            case "COMPLETE", "STREAM_CONNECT", "STREAM_BODY" -> "CHAT";
            case "EMBED", "EMBEDDING" -> "EMBEDDING";
            case "VERSION_SEMANTIC_COMPARE" -> "VERSION_SEMANTIC_COMPARE";
            case "TOOL_DECISION" -> "DIAGNOSIS_TOOL_DECISION";
            default -> "OTHER";
        };
    }
    private String bounded(String value) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_").substring(0, Math.min(40, value.length()));
    }
}
