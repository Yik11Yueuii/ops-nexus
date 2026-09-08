package com.opsnexus.evaluation;

import java.util.*;

/** Deterministic aggregation for the checked-in RAG evaluation fixture. */
final class RagEvaluationMetrics {
    record Hit(String document, String version) {}
    record Result(String id, boolean shouldAnswer, boolean shouldRefuse, String expectedDocument,
                  String expectedVersion, List<Hit> hits, long latencyNanos) {}
    record Summary(double recallAt1, double recallAt3, double recallAt5, double mrr,
                   double documentRate, double versionRate, double refusalCorrectness,
                   double citationDocumentCorrectness, double citationVersionCorrectness,
                   double latencyP50Ms, double latencyP95Ms) {}

    static Summary summarize(List<Result> results) {
        var answerable = results.stream().filter(Result::shouldAnswer).toList();
        var refusals = results.stream().filter(Result::shouldRefuse).toList();
        return new Summary(recall(answerable, 1), recall(answerable, 3), recall(answerable, 5), mrr(answerable),
            rate(answerable, r -> firstMatchesDocument(r)), rate(answerable, r -> firstMatchesVersion(r)),
            rate(refusals, r -> r.hits().isEmpty()), rate(answerable, RagEvaluationMetrics::firstMatchesDocument),
            rate(answerable, RagEvaluationMetrics::firstMatchesVersion), percentile(results, .50), percentile(results, .95));
    }
    private static double recall(List<Result> rows, int k) { return rate(rows, r -> r.hits().stream().limit(k).anyMatch(h -> expected(r, h))); }
    private static double mrr(List<Result> rows) { return rows.isEmpty() ? 0 : rows.stream().mapToDouble(r -> {
        for (int i = 0; i < r.hits().size(); i++) if (expected(r, r.hits().get(i))) return 1d / (i + 1); return 0;
    }).average().orElse(0); }
    private static boolean firstMatchesDocument(Result r) { return !r.hits().isEmpty() && r.expectedDocument().equals(r.hits().getFirst().document()); }
    private static boolean firstMatchesVersion(Result r) { return !r.hits().isEmpty() && expected(r, r.hits().getFirst()); }
    private static boolean expected(Result r, Hit h) { return r.expectedDocument().equals(h.document()) && r.expectedVersion().equals(h.version()); }
    private static double rate(List<Result> rows, java.util.function.Predicate<Result> test) { return rows.isEmpty() ? 0 : rows.stream().filter(test).count() / (double) rows.size(); }
    private static double percentile(List<Result> rows, double p) { if (rows.isEmpty()) return 0; var values = rows.stream().mapToLong(Result::latencyNanos).sorted().toArray(); return values[(int)Math.ceil(p * values.length) - 1] / 1_000_000d; }
}
