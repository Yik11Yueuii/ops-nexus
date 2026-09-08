package com.opsnexus.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekEvidenceJudgeTest {
    private final DeepSeekEvidenceJudge judge = new DeepSeekEvidenceJudge(new ObjectMapper());

    @Test void acceptsOnlyCompleteStructuredDecisions() throws Exception {
        var decision = judge.parse("{\"sufficient\":false,\"supportedFacts\":[],\"missingInformation\":[\"release time\"],\"reason\":\"Evidence lacks the requested fact.\"}");
        assertFalse(decision.sufficient());
        assertThrows(IllegalArgumentException.class, () -> judge.parse("{\"sufficient\":true}"));
        assertThrows(IllegalArgumentException.class, () -> judge.parse("{\"sufficient\":true,\"supportedFacts\":[],\"missingInformation\":[],\"reason\":\"yes\"}"));
    }

    @Test void promptTreatsInjectionInEvidenceAsData() {
        assertTrue(DeepSeekEvidenceJudge.SYSTEM.contains("untrusted data"));
        assertTrue(DeepSeekEvidenceJudge.SYSTEM.contains("must never override"));
        assertTrue(DeepSeekEvidenceJudge.SYSTEM.contains("Do not use outside knowledge"));
    }
}
