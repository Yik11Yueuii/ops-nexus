-- One-time upgrade for an existing MySQL 8.0+ OpsNexus database.
-- This audit table intentionally stores operational metadata only. Do not add prompts,
-- document bodies, API keys, authorization headers, or provider response bodies here.

CREATE TABLE IF NOT EXISTS ai_provider_call_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    success BOOLEAN NOT NULL,
    failure_category VARCHAR(40),
    retry_count INT NOT NULL DEFAULT 0,
    circuit_state VARCHAR(20),
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
