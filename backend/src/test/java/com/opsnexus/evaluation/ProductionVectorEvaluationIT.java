package com.opsnexus.evaluation;

import com.fasterxml.jackson.databind.*;
import com.opsnexus.ingestion.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.document.Document;

/** Opt-in evaluation of the unmodified production vector components. */
class ProductionVectorEvaluationIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record EvaluationCase(String id, String question, String expectedDocument, String expectedVersion, boolean shouldAnswer, boolean shouldRefuse) {}
    private record CorpusDocument(String id, String title, String version, String resource, boolean currentPublished) {}
    private record DetailedResult(RagEvaluationMetrics.Result metric, List<Map<String, Object>> rawHits) {}
    private static final List<CorpusDocument> CORPUS = List.of(
        new CorpusDocument("deploy-v2", "订单服务部署手册", "v2.0", "/samples/order-deploy-v2.md", true),
        new CorpusDocument("redis-sop", "Redis 连接池耗尽排障 SOP", "v1.0", "/samples/redis-sop.md", true),
        new CorpusDocument("release-guide", "服务发布与回滚规范", "v1.0", "/samples/release-guide.md", true),
        new CorpusDocument("deploy-v1", "订单服务部署手册", "v1.0", "/samples/order-deploy-v1.md", false));

    @Test void evaluatesCurrentVectorIndexWhenExplicitlyEnabled() throws Exception {
        String key = Objects.requireNonNullElse(System.getenv("DASHSCOPE_API_KEY"), "");
        Assumptions.assumeTrue(!key.isBlank(), "SKIPPED: DASHSCOPE_API_KEY is not available to the explicit production-vector-evaluation profile");
        String endpoint = Objects.requireNonNullElse(System.getenv("EMBEDDING_URL"), "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding");
        String model = Objects.requireNonNullElse(System.getenv("EMBEDDING_MODEL"), "text-embedding-v2");
        double threshold = Double.parseDouble(Objects.requireNonNullElse(System.getenv("RAG_SIMILARITY_THRESHOLD"), "0.45"));
        Path dataDir = Files.createTempDirectory("ops-vector-evaluation-");
        Files.createDirectories(dataDir.resolve("vectors")); // Mirrors VectorIndex @PostConstruct for direct test construction.
        var index = new VectorIndex(new DashScopeEmbedding(key, endpoint, model, JSON), JSON, dataDir.toString(), threshold);
        index.add(CORPUS.stream().flatMap(document -> asVectorDocuments(document).stream()).toList());
        var results = new ArrayList<DetailedResult>();
        for (var evaluationCase : cases()) results.add(evaluate(index, evaluationCase));
        var metrics = RagEvaluationMetrics.summarize(results.stream().map(DetailedResult::metric).toList());
        writeReport(metrics, results, endpoint, model, threshold);
        System.out.printf(Locale.ROOT, "Production-like VectorIndex baseline: cases=%d Recall@1=%.3f Recall@3=%.3f Recall@5=%.3f MRR=%.3f document=%.3f version=%.3f refusal=%.3f p50=%.3fms p95=%.3fms%n", results.size(), metrics.recallAt1(), metrics.recallAt3(), metrics.recallAt5(), metrics.mrr(), metrics.documentRate(), metrics.versionRate(), metrics.refusalCorrectness(), metrics.latencyP50Ms(), metrics.latencyP95Ms());
    }
    private List<Document> asVectorDocuments(CorpusDocument document) {
        var parts = new DocumentParser().parse(resource(document.resource()).getBytes(StandardCharsets.UTF_8), "MD");
        return parts.stream().map(part -> Document.builder().id(document.id() + "-chunk-" + part.index()).text(part.content()).metadata(Map.of("title", document.title(), "versionNo", document.version(), "currentPublished", document.currentPublished(), "chunkIndex", part.index())).build()).toList();
    }
    private DetailedResult evaluate(VectorIndex index, EvaluationCase evaluationCase) {
        long started = System.nanoTime(); var raw = index.search(evaluationCase.question(), 8); long elapsed = System.nanoTime() - started;
        var rawHits = raw.stream().map(document -> Map.<String, Object>of("vectorId", document.getId(), "document", document.getMetadata().get("title"), "version", document.getMetadata().get("versionNo"), "currentPublished", document.getMetadata().get("currentPublished"), "score", document.getScore())).toList();
        var filteredHits = raw.stream().filter(document -> Boolean.TRUE.equals(document.getMetadata().get("currentPublished"))).limit(5).map(document -> new RagEvaluationMetrics.Hit(document.getMetadata().get("title").toString(), document.getMetadata().get("versionNo").toString())).toList();
        return new DetailedResult(new RagEvaluationMetrics.Result(evaluationCase.id(), evaluationCase.shouldAnswer(), evaluationCase.shouldRefuse(), evaluationCase.expectedDocument(), evaluationCase.expectedVersion(), filteredHits, elapsed), rawHits);
    }
    private List<EvaluationCase> cases() throws IOException { try (var input = getClass().getResourceAsStream("/evaluation/rag-evaluation.json")) { var rows = JSON.readTree(Objects.requireNonNull(input)).path("cases"); var cases = new ArrayList<EvaluationCase>(); for (var row : rows) cases.add(new EvaluationCase(row.path("id").asText(), row.path("question").asText(), row.path("expectedDocument").asText(), row.path("expectedVersion").asText(), row.path("shouldAnswer").asBoolean(), row.path("shouldRefuse").asBoolean())); return cases; } }
    private String resource(String resource) { try (var input = getClass().getResourceAsStream(resource)) { return new String(Objects.requireNonNull(input).readAllBytes()); } catch (IOException e) { throw new UncheckedIOException(e); } }
    private void writeReport(RagEvaluationMetrics.Summary metrics, List<DetailedResult> results, String endpoint, String model, double threshold) throws IOException {
        var failures = results.stream().filter(result -> { var item = result.metric(); return item.shouldAnswer() ? item.hits().isEmpty() || !item.expectedDocument().equals(item.hits().getFirst().document()) || !item.expectedVersion().equals(item.hits().getFirst().version()) : !item.hits().isEmpty(); }).toList();
        var report = new LinkedHashMap<String, Object>(); report.put("kind", "production-like-vector-evaluation"); report.put("executedAt", Instant.now().toString()); report.put("gitCommit", gitCommit()); report.put("datasetVersion", "v1"); report.put("caseCount", results.size()); report.put("embeddingProvider", "DashScope"); report.put("embeddingModel", model); report.put("embeddingEndpoint", endpoint); report.put("vectorStore", "org.springframework.ai.vectorstore.SimpleVectorStore"); report.put("similarityThreshold", threshold); report.put("metrics", metrics); report.put("results", results); report.put("failures", failures);
        Path directory = Path.of("target", "rag-evaluation"); Files.createDirectories(directory); JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("production-vector-report.json").toFile(), report);
    }
    private String gitCommit() { try { var process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start(); if (process.waitFor() == 0) return new String(process.getInputStream().readAllBytes()).trim(); } catch (Exception ignored) { } return "unavailable"; }
}