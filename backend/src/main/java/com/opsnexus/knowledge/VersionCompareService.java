package com.opsnexus.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsnexus.assistant.DeepSeekChat;
import com.opsnexus.resilience.AiProviderException;
import com.opsnexus.observability.OpsNexusMetrics;
import com.opsnexus.security.PromptTrustBoundary;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes deterministic LCS facts before optionally asking the provider to explain the already
 * bounded change blocks. Provider failure never prevents the deterministic result from returning.
 */
@Service
public class VersionCompareService {
    private static final int MAX_LINES = 1200;
    private static final int MAX_SEMANTIC_BLOCKS = 12;
    private static final int MAX_SEMANTIC_BLOCK_CHARS = 800;
    private static final int MAX_SEMANTIC_TOTAL_CHARS = 5_000;
    private static final Set<String> CLASSIFICATIONS = Set.of("POLICY_CHANGE", "PROCEDURE_CHANGE",
        "CONFIGURATION_CHANGE", "THRESHOLD_CHANGE", "ROLE_OR_PERMISSION_CHANGE", "SLA_CHANGE",
        "RISK_OR_WARNING_CHANGE", "CLARIFICATION", "OTHER");
    private static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH", "UNKNOWN");

    /** The old content field remains for existing clients; oldText/newText add traceable facts. */
    public record Change(String blockId, String type, Integer oldLine, Integer newLine,
                         String oldText, String newText, String content) { }
    public record ChangeBlock(String id, String changeType, String oldVersion, String newVersion,
                              Integer oldLine, Integer newLine, String oldText, String newText) { }
    public record SemanticChangeItem(String changeBlockId, String changeType, String classification,
                                     String summary, String businessImpact, String risk,
                                     String oldMeaning, String newMeaning) { }
    public record SemanticVersionComparison(String overallSummary, List<SemanticChangeItem> changeItems,
                                            String riskLevel, List<String> affectedTopics, Instant generatedAt,
                                            String analysisStatus, String advisoryNotice) { }
    public record Result(long documentId, String title, String oldVersion, String newVersion, int added,
                         int deleted, int modified, List<Change> changes, List<ChangeBlock> changedBlocks,
                         List<String> riskHints, SemanticVersionComparison semanticComparison) { }

    private final JdbcTemplate db;
    private final DeepSeekChat chat;
    private final PromptTrustBoundary trustBoundary;
    private final ObjectMapper json;
    private final SemanticComparisonAuditService semanticAudit;
    private final OpsNexusMetrics metrics;

    public VersionCompareService(JdbcTemplate db, DeepSeekChat chat, PromptTrustBoundary trustBoundary,
                                 ObjectMapper json, SemanticComparisonAuditService semanticAudit, OpsNexusMetrics metrics) {
        this.db = db;
        this.chat = chat;
        this.trustBoundary = trustBoundary;
        this.json = json;
        this.semanticAudit = semanticAudit;
        this.metrics = metrics;
    }

    public Result compare(long oldId, long newId) {
        long started = System.nanoTime();
        if (oldId == newId) throw new KnowledgeException(400, "INVALID_INPUT", "请选择两个不同版本");
        DocumentMetadata metadata = metadata(oldId, newId);
        List<String> oldLines = lines(oldId);
        List<String> newLines = lines(newId);
        if (oldLines.size() > MAX_LINES || newLines.size() > MAX_LINES) {
            throw new KnowledgeException(413, "COMPARE_TOO_LARGE", "首版最多比较 1200 行");
        }
        DiffFacts facts = deterministicDiff(oldLines, newLines, metadata.oldVersion(), metadata.newVersion());
        Result result = new Result(metadata.documentId(), metadata.title(), metadata.oldVersion(), metadata.newVersion(),
            facts.added(), facts.deleted(), facts.modified(), facts.changes(), facts.blocks(),
            riskHints(facts.blocks()), analyze(metadata, facts.blocks()));
        String status = result.semanticComparison().analysisStatus();
        metrics.semantic(status.equals("AVAILABLE") ? "SEMANTIC_SUCCESS" : status.equals("NOT_REQUIRED") ? "DETERMINISTIC_ONLY" : "FALLBACK", status.equals("AVAILABLE") || status.equals("NOT_REQUIRED") ? null : status, elapsed(started));
        return result;
    }

    private DocumentMetadata metadata(long oldId, long newId) {
        List<Map<String, Object>> rows = db.queryForList("SELECT v.id,v.document_id,v.version_no,d.title FROM document_version v "
            + "JOIN knowledge_document d ON d.id=v.document_id WHERE v.id IN (?,?)", oldId, newId);
        if (rows.size() != 2) throw new KnowledgeException(404, "NOT_FOUND", "文档版本不存在");
        long documentId = number(rows.getFirst().get("DOCUMENT_ID"));
        if (rows.stream().anyMatch(row -> number(row.get("DOCUMENT_ID")) != documentId)) {
            throw new KnowledgeException(400, "VERSION_MISMATCH", "只能比较同一文档的版本");
        }
        return new DocumentMetadata(documentId, rows.getFirst().get("TITLE").toString(),
            version(rows, oldId), version(rows, newId), oldId, newId);
    }

    private DiffFacts deterministicDiff(List<String> oldLines, List<String> newLines, String oldVersion, String newVersion) {
        int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int oldIndex = oldLines.size() - 1; oldIndex >= 0; oldIndex--) {
            for (int newIndex = newLines.size() - 1; newIndex >= 0; newIndex--) {
                lcs[oldIndex][newIndex] = oldLines.get(oldIndex).equals(newLines.get(newIndex))
                    ? lcs[oldIndex + 1][newIndex + 1] + 1
                    : Math.max(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1]);
            }
        }
        List<RawChange> raw = new ArrayList<>();
        int oldIndex = 0, newIndex = 0, added = 0, deleted = 0;
        while (oldIndex < oldLines.size() || newIndex < newLines.size()) {
            if (oldIndex < oldLines.size() && newIndex < newLines.size() && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
                oldIndex++; newIndex++;
            } else if (newIndex < newLines.size() && (oldIndex == oldLines.size() || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex])) {
                raw.add(new RawChange("ADDED", null, newIndex + 1, null, newLines.get(newIndex++))); added++;
            } else {
                raw.add(new RawChange("DELETED", oldIndex + 1, null, oldLines.get(oldIndex++), null)); deleted++;
            }
        }
        List<Change> changes = new ArrayList<>();
        List<ChangeBlock> blocks = new ArrayList<>();
        int blockNumber = 1, modified = 0;
        for (int start = 0; start < raw.size();) {
            int end = start + 1;
            while (end < raw.size() && adjacent(raw.get(end - 1), raw.get(end))) end++;
            List<RawChange> oldGroup = raw.subList(start, end).stream().filter(item -> "DELETED".equals(item.type())).toList();
            List<RawChange> newGroup = raw.subList(start, end).stream().filter(item -> "ADDED".equals(item.type())).toList();
            int paired = Math.min(oldGroup.size(), newGroup.size());
            for (int index = 0; index < paired; index++) {
                RawChange before = oldGroup.get(index); RawChange after = newGroup.get(index); String id = blockId(blockNumber++);
                changes.add(change(id, "MODIFIED", before.oldLine(), after.newLine(), before.oldText(), after.newText()));
                blocks.add(block(id, "MODIFIED", oldVersion, newVersion, before.oldLine(), after.newLine(), before.oldText(), after.newText()));
                modified++;
            }
            for (int index = paired; index < oldGroup.size(); index++) {
                RawChange before = oldGroup.get(index); String id = blockId(blockNumber++);
                changes.add(change(id, "DELETED", before.oldLine(), null, before.oldText(), null));
                blocks.add(block(id, "DELETED", oldVersion, newVersion, before.oldLine(), null, before.oldText(), null));
            }
            for (int index = paired; index < newGroup.size(); index++) {
                RawChange after = newGroup.get(index); String id = blockId(blockNumber++);
                changes.add(change(id, "ADDED", null, after.newLine(), null, after.newText()));
                blocks.add(block(id, "ADDED", oldVersion, newVersion, null, after.newLine(), null, after.newText()));
            }
            start = end;
        }
        return new DiffFacts(added, deleted, modified, List.copyOf(changes), List.copyOf(blocks));
    }

    private boolean adjacent(RawChange left, RawChange right) {
        return (left.oldLine() == null || right.oldLine() == null || right.oldLine() == left.oldLine() + 1)
            && (left.newLine() == null || right.newLine() == null || right.newLine() == left.newLine() + 1);
    }

    private Change change(String id, String type, Integer oldLine, Integer newLine, String oldText, String newText) {
        String safeOld = safe(oldText), safeNew = safe(newText);
        String content = "ADDED".equals(type) ? safeNew : "DELETED".equals(type) ? safeOld : safeNew;
        return new Change(id, type, oldLine, newLine, safeOld, safeNew, content);
    }

    private ChangeBlock block(String id, String type, String oldVersion, String newVersion, Integer oldLine,
                              Integer newLine, String oldText, String newText) {
        return new ChangeBlock(id, type, oldVersion, newVersion, oldLine, newLine, safe(oldText), safe(newText));
    }

    private SemanticVersionComparison analyze(DocumentMetadata metadata, List<ChangeBlock> blocks) {
        Instant generatedAt = Instant.now();
        if (blocks.isEmpty()) return semantic("未检测到正文变更。", List.of(), "LOW", List.of(), generatedAt,
            "NOT_REQUIRED", "未调用 AI；两个版本的确定性差异为空。");
        if (inputTooLarge(blocks)) {
            semanticAudit.record(metadata.oldId(), metadata.newId(), "FALLBACK", "SEMANTIC_ANALYSIS_INPUT_TOO_LARGE", 0);
            return unavailable("SEMANTIC_ANALYSIS_INPUT_TOO_LARGE", generatedAt, "变更块超出语义分析输入上限；已保留完整确定性差异。");
        }
        if (!chat.configured()) {
            semanticAudit.record(metadata.oldId(), metadata.newId(), "FALLBACK", "AI_PROVIDER_NOT_CONFIGURED", 0);
            return unavailable("SEMANTIC_ANALYSIS_UNAVAILABLE", generatedAt, "AI 服务未配置；已保留完整确定性差异。");
        }
        long started = System.nanoTime();
        try {
            List<PromptTrustBoundary.UntrustedContext> contexts = blocks.stream().map(block ->
                new PromptTrustBoundary.UntrustedContext("version_change_" + block.id(), formatBlock(block))).toList();
            String output = chat.complete(trustBoundary.versionSemanticSystemPolicy(), contexts,
                "Analyze the supplied change blocks and return the required JSON only.", "VERSION_SEMANTIC_COMPARE");
            SemanticVersionComparison parsed = parseSemantic(output, blocks, generatedAt);
            semanticAudit.record(metadata.oldId(), metadata.newId(), "SUCCESS", null, elapsed(started));
            return parsed;
        } catch (AiProviderException error) {
            semanticAudit.record(metadata.oldId(), metadata.newId(), "FALLBACK", error.errorCode(), elapsed(started));
            return unavailable("SEMANTIC_ANALYSIS_UNAVAILABLE", generatedAt, "AI 服务暂不可用；已保留完整确定性差异。");
        } catch (Exception error) {
            semanticAudit.record(metadata.oldId(), metadata.newId(), "FALLBACK", "INVALID_RESPONSE", elapsed(started));
            return unavailable("SEMANTIC_ANALYSIS_INVALID_RESPONSE", generatedAt, "AI 返回未通过结构校验；已保留完整确定性差异。");
        }
    }

    private boolean inputTooLarge(List<ChangeBlock> blocks) {
        if (blocks.size() > MAX_SEMANTIC_BLOCKS) return true;
        int total = 0;
        for (ChangeBlock block : blocks) {
            int size = block.oldText().length() + block.newText().length();
            if (size > MAX_SEMANTIC_BLOCK_CHARS) return true;
            total += size;
        }
        return total > MAX_SEMANTIC_TOTAL_CHARS;
    }

    private SemanticVersionComparison parseSemantic(String output, List<ChangeBlock> blocks, Instant generatedAt) throws Exception {
        JsonNode root = json.readTree(output);
        if (!root.isObject()) throw new IllegalArgumentException("semantic response must be an object");
        String summary = required(root, "overallSummary", 800);
        String riskLevel = enumValue(root, "riskLevel", RISKS, false);
        List<String> topics = textArray(root.path("affectedTopics"), 12, 100);
        Set<String> ids = blocks.stream().map(ChangeBlock::id).collect(java.util.stream.Collectors.toSet());
        JsonNode rawItems = root.path("changeItems");
        if (!rawItems.isArray() || rawItems.isEmpty() || rawItems.size() > blocks.size()) throw new IllegalArgumentException("invalid changeItems");
        List<SemanticChangeItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : rawItems) {
            String id = required(item, "changeBlockId", 30);
            if (!ids.contains(id) || !seen.add(id)) throw new IllegalArgumentException("unknown or duplicate block id");
            ChangeBlock fact = blocks.stream().filter(block -> block.id().equals(id)).findFirst().orElseThrow();
            String type = required(item, "changeType", 20);
            if (!fact.changeType().equals(type)) throw new IllegalArgumentException("change type mismatch");
            items.add(new SemanticChangeItem(id, type, enumValue(item, "classification", CLASSIFICATIONS, false),
                required(item, "summary", 500), required(item, "businessImpact", 500),
                enumValue(item, "risk", RISKS, true), required(item, "oldMeaning", 400), required(item, "newMeaning", 400)));
        }
        return semantic(summary, List.copyOf(items), riskLevel, topics, generatedAt, "AVAILABLE",
            "AI 语义解释仅供参考；下方确定性变更事实为准。");
    }

    private SemanticVersionComparison unavailable(String status, Instant generatedAt, String notice) {
        return semantic("AI 语义分析当前不可用。", List.of(), "UNKNOWN", List.of(), generatedAt, status, notice);
    }

    private SemanticVersionComparison semantic(String summary, List<SemanticChangeItem> items, String risk,
                                               List<String> topics, Instant generatedAt, String status, String notice) {
        return new SemanticVersionComparison(summary, items, risk, List.copyOf(topics), generatedAt, status, notice);
    }

    private List<String> riskHints(List<ChangeBlock> blocks) {
        String joined = blocks.stream().map(block -> block.oldText() + " " + block.newText()).reduce("", (left, right) -> left + " " + right);
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (joined.matches(".*(端口|连接池|超时|线程|内存|CPU|配置).*")) hints.add("检测到运行参数变化：上线前应核对环境配置并进行容量验证。");
        if (joined.matches(".*(数据库|Redis|消息|缓存).*")) hints.add("检测到外部依赖变化：建议验证连接、降级和回滚路径。");
        if (joined.matches(".*(发布|灰度|回滚|审批).*")) hints.add("检测到发布流程变化：建议复核灰度范围、观察指标和回滚条件。");
        if (hints.isEmpty() && !blocks.isEmpty()) hints.add("存在正文变化：建议由文档负责人确认业务影响。");
        if (blocks.isEmpty()) hints.add("未检测到正文行级差异。");
        return List.copyOf(hints);
    }

    private List<String> lines(long id) {
        String text = db.queryForList("SELECT content FROM document_chunk_ref WHERE version_id=? ORDER BY chunk_index", id).stream()
            .map(row -> String.valueOf(row.get("CONTENT"))).reduce("", (left, right) -> left + "\n" + right);
        return Arrays.stream(text.split("\\R")).map(String::stripTrailing).filter(value -> !value.isBlank()).toList();
    }

    private String formatBlock(ChangeBlock block) {
        return "changeBlockId=" + block.id() + "\nchangeType=" + block.changeType() + "\noldVersion=" + block.oldVersion()
            + " oldLine=" + block.oldLine() + "\nnewVersion=" + block.newVersion() + " newLine=" + block.newLine()
            + "\noldText=" + block.oldText() + "\nnewText=" + block.newText();
    }

    private String required(JsonNode node, String field, int max) {
        String value = node.path(field).isTextual() ? node.path(field).asText().strip() : "";
        if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException("invalid " + field);
        return value;
    }

    private String enumValue(JsonNode node, String field, Set<String> allowed, boolean allowUnknown) {
        String value = required(node, field, 50);
        if (!allowed.contains(value) || (!allowUnknown && "UNKNOWN".equals(value))) throw new IllegalArgumentException("invalid " + field);
        return value;
    }

    private List<String> textArray(JsonNode node, int maxItems, int maxChars) {
        if (!node.isArray() || node.size() > maxItems) throw new IllegalArgumentException("invalid affectedTopics");
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText().strip() : "";
            if (value.isEmpty() || value.length() > maxChars) throw new IllegalArgumentException("invalid topic");
            result.add(value);
        }
        return List.copyOf(result);
    }

    private String version(List<Map<String, Object>> rows, long id) {
        return rows.stream().filter(row -> number(row.get("ID")) == id).findFirst().orElseThrow().get("VERSION_NO").toString();
    }
    private String blockId(int value) { return "CHANGE-%03d".formatted(value); }
    private String safe(String value) { return trustBoundary.redactSecrets(value == null ? "" : value); }
    private long number(Object value) { return ((Number) value).longValue(); }
    private long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }

    private record RawChange(String type, Integer oldLine, Integer newLine, String oldText, String newText) { }
    private record DiffFacts(int added, int deleted, int modified, List<Change> changes, List<ChangeBlock> blocks) { }
    private record DocumentMetadata(long documentId, String title, String oldVersion, String newVersion, long oldId, long newId) { }
}
