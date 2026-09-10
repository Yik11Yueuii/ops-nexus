package com.opsnexus.resilience;

import com.opsnexus.observability.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Records provider metadata only; prompts, document bodies, and credentials never enter this audit. */
@Service
public class AiProviderAuditService {
    private final JdbcTemplate db;

    /** Test-only compatibility constructor: production uses the JdbcTemplate-injected constructor. */
    public AiProviderAuditService() {
        this.db = null;
    }

    @Autowired
    public AiProviderAuditService(JdbcTemplate db) {
        this.db = db;
    }

    public void record(AiProvider provider, String operation, boolean success, AiProviderFailure failure,
            int retryCount, String circuitState, long latencyMs) {
        if (db == null) {
            return;
        }
        try {
            db.update("INSERT INTO ai_provider_call_audit(provider,operation,success,failure_category,retry_count,circuit_state,latency_ms,trace_id) VALUES(?,?,?,?,?,?,?,?)",
                provider.name(), operation, success, failure == null ? null : failure.name(), retryCount, circuitState, latencyMs, TraceContext.current());
        } catch (RuntimeException ignored) {
            // Observability must not turn a successful provider call into an application failure.
        }
    }
}
