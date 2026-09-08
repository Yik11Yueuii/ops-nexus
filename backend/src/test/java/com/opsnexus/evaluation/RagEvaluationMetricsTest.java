package com.opsnexus.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RagEvaluationMetricsTest {
    @Test void calculatesRetrievalPolicyCitationAndLatencyMetrics() {
        var rows = List.of(
            new RagEvaluationMetrics.Result("a", true, false, "A", "v1", List.of(new RagEvaluationMetrics.Hit("A", "v1")), 1_000_000),
            new RagEvaluationMetrics.Result("b", true, false, "B", "v2", List.of(new RagEvaluationMetrics.Hit("A", "v1"), new RagEvaluationMetrics.Hit("B", "v2")), 3_000_000),
            new RagEvaluationMetrics.Result("c", false, true, "", "", List.of(), 2_000_000),
            new RagEvaluationMetrics.Result("d", false, true, "", "", List.of(new RagEvaluationMetrics.Hit("A", "v1")), 4_000_000));
        var s = RagEvaluationMetrics.summarize(rows);
        assertEquals(.5, s.recallAt1()); assertEquals(1, s.recallAt3()); assertEquals(.75, s.mrr());
        assertEquals(.5, s.documentRate()); assertEquals(.5, s.versionRate()); assertEquals(.5, s.refusalCorrectness());
        assertEquals(2, s.latencyP50Ms()); assertEquals(4, s.latencyP95Ms());
    }
}
