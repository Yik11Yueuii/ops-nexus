package com.opsnexus.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/observability")
public class ObservabilityController {
    private final MeterRegistry registry;
    public ObservabilityController(MeterRegistry registry) { this.registry = registry; }
    @GetMapping("/summary") public Map<String, Object> summary() {
        return Map.of("aiCalls", count("opsnexus.ai.calls"), "ragRequests", count("opsnexus.rag.requests"),
            "toolCalls", count("opsnexus.tool.calls"), "sqlQueries", count("opsnexus.sql.queries"),
            "semanticComparisons", count("opsnexus.semantic.comparisons"), "ingestionEvents", count("opsnexus.ingestion.events"));
    }
    private double count(String name) { return registry.find(name).counters().stream().mapToDouble(item -> item.count()).sum(); }
}
