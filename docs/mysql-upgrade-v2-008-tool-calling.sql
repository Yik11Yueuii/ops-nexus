-- OPS-V2-008: apply to existing MySQL databases before deploying diagnosis tool calling.
CREATE TABLE IF NOT EXISTS diagnosis_tool_call_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    diagnosis_request_id VARCHAR(50) NOT NULL,
    tool_name VARCHAR(80) NOT NULL,
    model_requested BOOLEAN NOT NULL,
    authorization_granted BOOLEAN NOT NULL,
    success BOOLEAN NOT NULL,
    failure_category VARCHAR(50),
    duration_ms BIGINT NOT NULL,
    result_size INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_diagnosis_tool_audit_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
