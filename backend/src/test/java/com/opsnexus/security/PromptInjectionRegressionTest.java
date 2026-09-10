package com.opsnexus.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.knowledge.KnowledgeException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed, offline containment baseline; it never invokes an external model. */
class PromptInjectionRegressionTest {
    private final PromptTrustBoundary boundary = new PromptTrustBoundary();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fixedInjectionRegressionSetPassesByCategory() throws Exception {
        try (var input = getClass().getResourceAsStream("/security/prompt-injection-regression.json")) {
            assertNotNull(input, "prompt-injection regression dataset missing");
            JsonNode cases = json.readTree(input).path("cases");
            assertEquals(24, cases.size());
            Map<String, int[]> results = new LinkedHashMap<>();
            for (JsonNode item : cases) {
                String category = item.path("category").asText();
                String payload = item.path("payload").asText();
                String expected = item.path("expected").asText();
                int[] count = results.computeIfAbsent(category, unused -> new int[2]);
                count[1]++;
                if ("BLOCK".equals(expected)) {
                    KnowledgeException error = assertThrows(KnowledgeException.class, () -> boundary.rejectDirectDisclosure(payload), item.path("id").asText());
                    assertEquals("PROMPT_DISCLOSURE_DENIED", error.code);
                } else {
                    if ("DIRECT_USER_INJECTION".equals(category)) {
                        assertDoesNotThrow(() -> boundary.rejectDirectDisclosure(payload), item.path("id").asText());
                    }
                    String wrapped = boundary.wrap(new PromptTrustBoundary.UntrustedContext("retrieved_evidence", payload));
                    assertTrue(wrapped.startsWith("<untrusted_context source=\"retrieved_evidence\">"));
                    assertTrue(wrapped.endsWith("</untrusted_context>"));
                    assertFalse(wrapped.contains("</untrusted_context><"), "untrusted content must not close its wrapper");
                    if (payload.contains("test-secret")) assertFalse(wrapped.contains("test-secret"));
                }
                count[0]++;
            }
            assertEquals(6, results.size());
            assertTrue(results.values().stream().allMatch(count -> count[0] == count[1]));
            int total = results.values().stream().mapToInt(count -> count[1]).sum();
            int passed = results.values().stream().mapToInt(count -> count[0]).sum();
            System.out.println("Prompt injection regression: total=" + total + " passed=" + passed + " failed=" + (total - passed));
            results.forEach((category, count) -> System.out.println(category + " passRate=" + count[0] + "/" + count[1]));
        }
    }
}
