package com.opsnexus.knowledge;

import com.opsnexus.observability.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stores comparison metadata only; document text and provider prompts are intentionally excluded. */
@Service
public class SemanticComparisonAuditService {
    private final JdbcTemplate db;

    public SemanticComparisonAuditService(JdbcTemplate db) {
        this.db = db;
    }

    public void record(long oldVersionId, long newVersionId, String status, String failureCategory, long latencyMs) {
        try {
            db.update("INSERT INTO version_semantic_compare_audit(old_version_id,new_version_id,provider,operation,status,failure_category,latency_ms,trace_id) VALUES(?,?,?,?,?,?,?,?)",
                oldVersionId, newVersionId, "DEEPSEEK_CHAT", "VERSION_SEMANTIC_COMPARE", status, failureCategory, latencyMs, TraceContext.current());
        } catch (RuntimeException ignored) {
            // Audit unavailability must not hide the deterministic comparison result.
        }
    }
}
