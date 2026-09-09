package com.opsnexus.governance;

import com.opsnexus.assistant.EvidenceRetrievalService;
import com.opsnexus.assistant.AssistantService.Evidence;
import com.opsnexus.knowledge.KnowledgeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates traceable, deterministic knowledge-gap lifecycle changes. */
@Service
public class KnowledgeGapService {
    public record Occurrence(long knowledgeBaseId, Long conversationId, Long questionMessageId, Long assistantMessageId,
                             Long feedbackId, String question, String sourceType, String evidenceSummary) {}

    private static final Set<String> SOURCES = Set.of("NO_EVIDENCE", "LOW_CONFIDENCE", "USER_DOWNVOTE");
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final EvidenceRetrievalService retrieval;

    public KnowledgeGapService(JdbcTemplate db, TransactionTemplate transactions, EvidenceRetrievalService retrieval) {
        this.db = db;
        this.transactions = transactions;
        this.retrieval = retrieval;
    }

    /** Compatibility entry point for the original no-evidence recorder. */
    public void record(long knowledgeBaseId, String question) {
        record(new Occurrence(knowledgeBaseId, null, null, null, null, question, "NO_EVIDENCE", "未检索到符合当前已发布版本的证据"));
    }

    /** Adds one real occurrence, reopening a resolved aggregate when the failure recurs. */
    public synchronized void record(Occurrence occurrence) {
        validateSource(occurrence.sourceType());
        if (occurrence.feedbackId() != null && db.queryForObject("SELECT COUNT(*) FROM gap_occurrence WHERE feedback_id=?", Integer.class, occurrence.feedbackId()) > 0) return;
        transactions.executeWithoutResult(ignored -> recordInternal(occurrence));
    }

    /** Records a DOWN feedback once for its immutable feedback row. */
    public void recordDownvote(long assistantMessageId, long feedbackId) {
        var rows = db.queryForList("""
            SELECT a.conversation_id,COALESCE(a.kb_id,(SELECT d.kb_id FROM message_citation c JOIN document_version v ON v.id=c.version_id JOIN knowledge_document d ON d.id=v.document_id WHERE c.message_id=a.id ORDER BY c.id LIMIT 1)) AS kb_id,a.confidence_level,a.evidence_sufficiency,
              (SELECT q.id FROM chat_message q WHERE q.conversation_id=a.conversation_id AND q.role='USER' AND q.id<a.id ORDER BY q.id DESC LIMIT 1) AS question_message_id,
              (SELECT q.content FROM chat_message q WHERE q.conversation_id=a.conversation_id AND q.role='USER' AND q.id<a.id ORDER BY q.id DESC LIMIT 1) AS question
            FROM chat_message a WHERE a.id=? AND a.role='ASSISTANT'
            """, assistantMessageId);
        // Legacy messages may predate kb_id. Preserve feedback when no trustworthy gap source can be reconstructed.
        if (rows.isEmpty() || rows.getFirst().get("KB_ID") == null || rows.getFirst().get("QUESTION") == null) return;
        var row = rows.getFirst();
        String summary = "用户点踩；回答可信等级=" + Objects.toString(row.get("CONFIDENCE_LEVEL"), "未知")
            + "，证据充分性=" + Objects.toString(row.get("EVIDENCE_SUFFICIENCY"), "未知");
        record(new Occurrence(((Number) row.get("KB_ID")).longValue(), ((Number) row.get("CONVERSATION_ID")).longValue(),
            ((Number) row.get("QUESTION_MESSAGE_ID")).longValue(), assistantMessageId, feedbackId, row.get("QUESTION").toString(), "USER_DOWNVOTE", summary));
    }

    public List<Map<String, Object>> list() {
        return db.queryForList("""
            SELECT g.id,g.kb_id,k.name AS knowledge_base,g.sample_question,g.source_type,g.priority,g.occurrence_count,g.status,
              g.first_seen_at,g.last_seen_at,g.processing_at,g.resolved_at,g.last_verification_result,
              (SELECT v.result FROM gap_verification v WHERE v.gap_id=g.id ORDER BY v.id DESC LIMIT 1) AS latest_verification_result
            FROM knowledge_gap g JOIN knowledge_base k ON k.id=g.kb_id
            ORDER BY CASE WHEN g.status='OPEN' THEN 0 WHEN g.status='PROCESSING' THEN 1 ELSE 2 END,
              CASE g.priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,g.occurrence_count DESC,g.last_seen_at DESC
            """);
    }

    public Map<String, Object> detail(long gapId) {
        var gaps = db.queryForList("SELECT * FROM knowledge_gap WHERE id=?", gapId);
        if (gaps.isEmpty()) throw notFound();
        var result = new LinkedHashMap<String, Object>();
        result.put("gap", gaps.getFirst());
        result.put("occurrences", db.queryForList("SELECT id,conversation_id,question_message_id,assistant_message_id,question,source_type,evidence_summary,created_at FROM gap_occurrence WHERE gap_id=? ORDER BY id DESC", gapId));
        result.put("verifications", db.queryForList("SELECT id,question,evidence_sufficient,result,retrieval_summary,operator_id,notes,created_at FROM gap_verification WHERE gap_id=? ORDER BY id DESC", gapId));
        return result;
    }

    public Map<String, Object> stats() {
        return Map.of(
            "open", count("OPEN"), "processing", count("PROCESSING"), "resolved", count("RESOLVED"),
            "occurrences", db.queryForObject("SELECT COALESCE(SUM(occurrence_count),0) FROM knowledge_gap", Long.class));
    }

    public void startProcessing(long gapId, long operatorId, String note) {
        requireNote(note, "处理说明");
        if (db.update("UPDATE knowledge_gap SET status='PROCESSING',processing_note=?,processing_by=?,processing_at=CURRENT_TIMESTAMP WHERE id=? AND status='OPEN'", note.strip(), operatorId, gapId) == 0) {
            ensureExists(gapId); throw new KnowledgeException(409, "INVALID_GAP_TRANSITION", "只有 OPEN 状态可以标记为处理中");
        }
    }

    /** Re-runs only the existing production VectorIndex retrieval pipeline. */
    public Map<String, Object> revalidate(long gapId, long operatorId, String notes) {
        var rows = db.queryForList("SELECT kb_id,sample_question,status FROM knowledge_gap WHERE id=?", gapId);
        if (rows.isEmpty()) throw notFound();
        var gap = rows.getFirst();
        if (!"PROCESSING".equals(gap.get("STATUS"))) throw new KnowledgeException(409, "INVALID_GAP_TRANSITION", "只有 PROCESSING 状态可以重新验证");
        List<Evidence> evidence = retrieval.retrieve(((Number) gap.get("KB_ID")).longValue(), gap.get("SAMPLE_QUESTION").toString());
        boolean sufficient = !evidence.isEmpty();
        String result = sufficient ? "EVIDENCE_FOUND" : "NO_EVIDENCE";
        String summary = summarize(evidence);
        db.update("INSERT INTO gap_verification(gap_id,question,evidence_sufficient,result,retrieval_summary,operator_id,notes) VALUES(?,?,?,?,?,?,?)", gapId, gap.get("SAMPLE_QUESTION"), sufficient, result, summary, operatorId, nullableNote(notes));
        db.update("UPDATE knowledge_gap SET last_verification_result=? WHERE id=?", result, gapId);
        return Map.of("result", result, "evidenceSufficient", sufficient, "evidence", evidence);
    }

    public void resolve(long gapId, long operatorId, String resolutionType, String note) {
        if (resolutionType == null || !Set.of("VERIFIED_RESOLUTION", "MANUAL_RESOLUTION").contains(resolutionType)) throw new KnowledgeException(400, "INVALID_INPUT", "关闭类型不合法");
        requireNote(note, "解决说明");
        var rows = db.queryForList("SELECT status,last_verification_result FROM knowledge_gap WHERE id=?", gapId);
        if (rows.isEmpty()) throw notFound();
        var gap = rows.getFirst();
        if (!"PROCESSING".equals(gap.get("STATUS"))) throw new KnowledgeException(409, "INVALID_GAP_TRANSITION", "只有 PROCESSING 状态可以关闭");
        if ("VERIFIED_RESOLUTION".equals(resolutionType) && !"EVIDENCE_FOUND".equals(gap.get("LAST_VERIFICATION_RESULT"))) throw new KnowledgeException(409, "VERIFICATION_REQUIRED", "验证命中证据后才能按验证通过关闭");
        db.update("UPDATE knowledge_gap SET status='RESOLVED',resolution_type=?,resolution_note=?,resolved_by=?,resolved_at=CURRENT_TIMESTAMP WHERE id=?", resolutionType, note.strip(), operatorId, gapId);
    }

    private void recordInternal(Occurrence input) {
        String question = bounded(input.question(), 1000);
        String normalized = normalize(question);
        var matches = db.queryForList("SELECT id,status,source_type,priority FROM knowledge_gap WHERE kb_id=? AND normalized_question=?", input.knowledgeBaseId(), normalized);
        long gapId;
        if (matches.isEmpty()) {
            gapId = insert("INSERT INTO knowledge_gap(kb_id,normalized_question,sample_question,source_type,priority) VALUES(?,?,?,?,?)", input.knowledgeBaseId(), normalized, question, input.sourceType(), defaultPriority(input.sourceType()));
        } else {
            var existing = matches.getFirst();
            gapId = ((Number) existing.get("ID")).longValue();
            String source = higherSource(existing.get("SOURCE_TYPE").toString(), input.sourceType());
            String priority = higherPriority(existing.get("PRIORITY").toString(), defaultPriority(input.sourceType()));
            db.update("UPDATE knowledge_gap SET occurrence_count=occurrence_count+1,last_seen_at=CURRENT_TIMESTAMP,status='OPEN',source_type=?,priority=?,processing_note=NULL,processing_at=NULL,processing_by=NULL,resolution_type=NULL,resolution_note=NULL,resolved_at=NULL,resolved_by=NULL,last_verification_result=NULL WHERE id=?", source, priority, gapId);
        }
        db.update("INSERT INTO gap_occurrence(gap_id,conversation_id,question_message_id,assistant_message_id,feedback_id,question,source_type,evidence_summary) VALUES(?,?,?,?,?,?,?,?)", gapId, input.conversationId(), input.questionMessageId(), input.assistantMessageId(), input.feedbackId(), question, input.sourceType(), optionalBounded(input.evidenceSummary(), 500));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(connection -> { var statement = connection.prepareStatement(sql, new String[]{"id"}); for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]); return statement; }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private String summarize(List<Evidence> evidence) {
        if (evidence.isEmpty()) return "未命中当前已发布版本的有效证据";
        var parts = new ArrayList<String>();
        for (var item : evidence) parts.add(item.title() + " " + item.versionNo() + " score=" + String.format(Locale.ROOT, "%.3f", item.score()));
        return bounded(String.join("；", parts), 1000);
    }
    private long count(String status) { return db.queryForObject("SELECT COUNT(*) FROM knowledge_gap WHERE status=?", Long.class, status); }
    private void ensureExists(long id) { if (db.queryForObject("SELECT COUNT(*) FROM knowledge_gap WHERE id=?", Integer.class, id) == 0) throw notFound(); }
    private KnowledgeException notFound() { return new KnowledgeException(404, "NOT_FOUND", "知识缺口不存在"); }
    private void validateSource(String source) { if (source == null || !SOURCES.contains(source)) throw new KnowledgeException(400, "INVALID_INPUT", "缺口来源不合法"); }
    private void requireNote(String note, String field) { if (note == null || note.isBlank() || note.strip().length() > 500) throw new KnowledgeException(400, "INVALID_INPUT", field + "不能为空且不能超过 500 字"); }
    private String nullableNote(String note) { return note == null || note.isBlank() ? null : bounded(note, 500); }
    private String bounded(String value, int max) { String result = Objects.requireNonNullElse(value, "").strip(); if (result.isEmpty()) throw new KnowledgeException(400, "INVALID_INPUT", "问题不能为空"); return result.length() > max ? result.substring(0, max) : result; }
    private String optionalBounded(String value, int max) { String result = Objects.requireNonNullElse(value, "").strip(); return result.isEmpty() ? null : (result.length() > max ? result.substring(0, max) : result); }
    private String normalize(String question) { return question.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private String defaultPriority(String source) { return "NO_EVIDENCE".equals(source) ? "HIGH" : "MEDIUM"; }
    private String higherSource(String left, String right) { return sourceRank(right) > sourceRank(left) ? right : left; }
    private int sourceRank(String source) { return switch (source) { case "NO_EVIDENCE" -> 3; case "LOW_CONFIDENCE" -> 2; default -> 1; }; }
    private String higherPriority(String left, String right) { return priorityRank(right) > priorityRank(left) ? right : left; }
    private int priorityRank(String priority) { return switch (priority) { case "HIGH" -> 3; case "MEDIUM" -> 2; default -> 1; }; }
}