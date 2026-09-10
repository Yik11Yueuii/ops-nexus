package com.opsnexus.assistant;

import com.opsnexus.observability.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Stores tool-call metadata only; arguments and result bodies are deliberately excluded. */
@Service
public class DiagnosisToolAuditService {
    private final JdbcTemplate db;

    public DiagnosisToolAuditService(JdbcTemplate db) {
        this.db = db;
    }

    public void record(long userId, String diagnosisRequestId, String toolName, boolean authorized,
            boolean success, String failureCategory, long durationMs, int resultSize) {
        db.update("INSERT INTO diagnosis_tool_call_audit(user_id,diagnosis_request_id,tool_name,model_requested,authorization_granted,success,failure_category,duration_ms,result_size,trace_id) VALUES(?,?,?,?,?,?,?,?,?,?)",
            userId, diagnosisRequestId, toolName, true, authorized, success, failureCategory, durationMs, resultSize, TraceContext.current());
    }
}
