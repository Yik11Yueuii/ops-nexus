package com.opsnexus;

import com.opsnexus.observability.OpsNexusMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:observability-test;DB_CLOSE_DELAY=-1","ops.seed-samples=false","ops.data-dir=./target/observability-test-runtime"})
@AutoConfigureMockMvc
class ObservabilityRegressionTest {
    @Autowired MockMvc mvc; @Autowired OpsNexusMetrics metrics; @Autowired MeterRegistry registry;
    static Stream<String> cases() { return Stream.of("AI_SUCCESS","AI_FAILURE","RAG_HIT","RAG_NO_HIT","TOOL_REQUESTED","TOOL_SUCCESS","TOOL_DENIED","TOOL_VALIDATION","TOOL_TIMEOUT","SQL_ATTEMPT","SQL_ALLOWED","SQL_REJECTED","SQL_FAILURE","SEMANTIC_SUCCESS","SEMANTIC_FALLBACK","SEMANTIC_DETERMINISTIC","INGEST_SUBMIT","INGEST_PROCESS","INGEST_SUCCESS","INGEST_FAILURE"); }
    @ParameterizedTest(name="{0}") @MethodSource("cases") void recordsBoundedBusinessMetrics(String testCase) {
        metrics.ai("DEEPSEEK_CHAT", "COMPLETE", true, null, 1);
        metrics.rag(8, testCase.contains("NO_HIT") ? 0 : 1, "HIT", 1);
        metrics.tool(testCase.contains("TOOL") ? "lookup_service_status" : "untrusted-name", "EXECUTED", true, null, 1);
        metrics.sql("EXECUTION", true, null, 1); metrics.semantic("SEMANTIC_SUCCESS", null, 1); metrics.ingestion("COMPLETED", true, 1);
        assertTrue(registry.find("opsnexus.ai.calls").counters().stream().mapToDouble(counter -> counter.count()).sum() > 0);
        assertTrue(registry.find("opsnexus.tool.calls").counters().stream().allMatch(counter -> counter.getId().getTag("tool") == null || counter.getId().getTag("tool").equals("UNKNOWN") || counter.getId().getTag("tool").startsWith("lookup_")));
    }
    @org.junit.jupiter.api.Test void traceHealthAndActuatorPermissions() throws Exception {
        mvc.perform(get("/api/health").header("X-Trace-Id", "trace-safe_123"))
            .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", "trace-safe_123"));
        mvc.perform(get("/api/health").header("X-Trace-Id", "bad value"))
            .andExpect(status().isOk()).andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9][A-Za-z0-9._-]{7,63}")));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }
}
