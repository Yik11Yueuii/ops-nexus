package com.opsnexus.evaluation;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline baseline: fixed, checked-in corpus and lexical embedding surrogate. It deliberately
 * does not call DashScope or DeepSeek, so it is repeatable and isolates retrieval/version policy.
 */
class RagEvaluationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LATIN = Pattern.compile("[a-z0-9-]{2,}");
    private static final List<CorpusDocument> CORPUS = List.of(
        document("订单服务部署手册", "v2.0", "/samples/order-deploy-v2.md", true),
        document("Redis 连接池耗尽排障 SOP", "v1.0", "/samples/redis-sop.md", true),
        document("服务发布与回滚规范", "v1.0", "/samples/release-guide.md", true),
        document("订单服务部署手册", "v1.0", "/samples/order-deploy-v1.md", false));

    record EvaluationCase(String id, String category, String question, String expectedDocument,
                          String expectedVersion, List<String> expectedKeywords, boolean shouldAnswer,
                          boolean shouldRefuse, String notes) {}
    record CorpusDocument(String title, String version, String text, boolean currentPublished) {}

    @Test void evaluationDatasetHasRequiredFieldsAndOnlyCurrentExpectedVersions() throws Exception {
        var cases = cases();
        assertEquals(32, cases.size());
        assertEquals(cases.size(), cases.stream().map(EvaluationCase::id).distinct().count());
        assertTrue(cases.stream().anyMatch(c -> c.shouldRefuse() && c.expectedDocument().isBlank()));
        assertTrue(cases.stream().filter(EvaluationCase::shouldAnswer).allMatch(c -> CORPUS.stream()
            .anyMatch(d -> d.currentPublished() && d.title().equals(c.expectedDocument()) && d.version().equals(c.expectedVersion()))));
        assertTrue(cases.stream().allMatch(c -> !c.question().isBlank() && !c.category().isBlank() && !c.notes().isBlank()));
    }

    @Test void evaluatesFixedRetrievalVersionAndRefusalPolicyRepeatably() throws Exception {
        var first = evaluate(cases());
        var second = evaluate(cases());
        assertEquals(first.stream().map(r -> r.hits().toString()).toList(), second.stream().map(r -> r.hits().toString()).toList());
        assertTrue(first.stream().filter(RagEvaluationMetrics.Result::shouldAnswer).allMatch(r -> r.hits().stream()
            .noneMatch(h -> h.document().equals("订单服务部署手册") && h.version().equals("v1.0"))), "归档版本不得作为候选证据");
        writeReport(first);
        var metrics = RagEvaluationMetrics.summarize(first);
        System.out.printf(Locale.ROOT, "RAG evaluation baseline: cases=%d Recall@1=%.3f Recall@3=%.3f Recall@5=%.3f MRR=%.3f document=%.3f version=%.3f refusal=%.3f citationDoc=%.3f citationVersion=%.3f p50=%.3fms p95=%.3fms%n",
            first.size(), metrics.recallAt1(), metrics.recallAt3(), metrics.recallAt5(), metrics.mrr(), metrics.documentRate(), metrics.versionRate(), metrics.refusalCorrectness(), metrics.citationDocumentCorrectness(), metrics.citationVersionCorrectness(), metrics.latencyP50Ms(), metrics.latencyP95Ms());
    }

    private static List<EvaluationCase> cases() throws Exception {
        try (var in = RagEvaluationTest.class.getResourceAsStream("/evaluation/rag-evaluation.json")) {
            assertNotNull(in, "evaluation dataset missing");
            var root = JSON.readTree(in); assertEquals("v1", root.path("datasetVersion").asText());
            var result = new ArrayList<EvaluationCase>();
            for (var row : root.path("cases")) result.add(new EvaluationCase(row.path("id").asText(), row.path("category").asText(), row.path("question").asText(),
                row.path("expectedDocument").asText(), row.path("expectedVersion").asText(), JSON.convertValue(row.path("expectedKeywords"), JSON.getTypeFactory().constructCollectionType(List.class, String.class)),
                row.path("shouldAnswer").asBoolean(), row.path("shouldRefuse").asBoolean(), row.path("notes").asText()));
            return result;
        }
    }
    private static List<RagEvaluationMetrics.Result> evaluate(List<EvaluationCase> cases) {
        return cases.stream().map(c -> { long start = System.nanoTime(); var hits = retrieve(c.question()); long elapsed = System.nanoTime() - start;
            return new RagEvaluationMetrics.Result(c.id(), c.shouldAnswer(), c.shouldRefuse(), c.expectedDocument(), c.expectedVersion(), hits, elapsed); }).toList();
    }
    private static List<RagEvaluationMetrics.Hit> retrieve(String question) {
        var query = terms(question);
        return CORPUS.stream().filter(CorpusDocument::currentPublished).map(d -> Map.entry(d, score(query, terms(d.text()))))
            .filter(e -> e.getValue() > 0).sorted(Comparator.<Map.Entry<CorpusDocument, Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(e -> e.getKey().title()))
            .limit(5).map(e -> new RagEvaluationMetrics.Hit(e.getKey().title(), e.getKey().version())).toList();
    }
    private static int score(Set<String> query, Set<String> text) { return (int) query.stream().filter(text::contains).count(); }
    private static Set<String> terms(String text) {
        var values = new LinkedHashSet<String>(); var normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        var latin = LATIN.matcher(normalized); while (latin.find()) values.add(latin.group());
        var chars = normalized.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).toArray();
        for (int i = 0; i + 1 < chars.length; i++) values.add(new String(chars, i, 2));
        return values;
    }
    private static CorpusDocument document(String title, String version, String resource, boolean current) {
        try (var in = RagEvaluationTest.class.getResourceAsStream(resource)) { return new CorpusDocument(title, version, new String(Objects.requireNonNull(in).readAllBytes()), current); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private static void writeReport(List<RagEvaluationMetrics.Result> results) throws IOException {
        var dir = Path.of("target", "rag-evaluation"); Files.createDirectories(dir);
        var failures = results.stream().filter(r -> r.shouldAnswer() ? r.hits().isEmpty() || !r.hits().getFirst().document().equals(r.expectedDocument()) || !r.hits().getFirst().version().equals(r.expectedVersion()) : !r.hits().isEmpty()).toList();
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("report.json").toFile(), Map.of("metrics", RagEvaluationMetrics.summarize(results), "results", results, "failures", failures));
    }
}
