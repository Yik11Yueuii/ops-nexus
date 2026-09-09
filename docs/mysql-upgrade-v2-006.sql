-- One-time upgrade for an existing MySQL 8.0+ OpsNexus database.
-- Take a backup and run the preflight checks below before executing any ALTER statement.
-- This script intentionally does not disable FOREIGN_KEY_CHECKS or invent data repairs.

-- Preflight: each query must return zero rows before adding its corresponding foreign key.
SELECT d.id FROM knowledge_document d LEFT JOIN knowledge_base k ON k.id=d.kb_id WHERE k.id IS NULL;
SELECT v.id FROM document_version v LEFT JOIN knowledge_document d ON d.id=v.document_id WHERE d.id IS NULL;
SELECT o.id FROM gap_occurrence o LEFT JOIN knowledge_gap g ON g.id=o.gap_id WHERE g.id IS NULL;
SELECT v.id FROM gap_verification v LEFT JOIN knowledge_gap g ON g.id=v.gap_id WHERE g.id IS NULL;
SELECT f.id FROM message_feedback f LEFT JOIN chat_message m ON m.id=f.message_id WHERE m.id IS NULL;

-- Replace the legacy 255-character prefix key with exact normalized-question identity.
ALTER TABLE knowledge_gap ADD COLUMN normalized_question_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(normalized_question,256))) STORED;
ALTER TABLE knowledge_gap DROP INDEX uk_gap_question;
ALTER TABLE knowledge_gap ADD CONSTRAINT uk_gap_question UNIQUE (kb_id,normalized_question_hash);

-- Apply only after the preflight result is clean. Name conflicts mean the constraint already exists.
ALTER TABLE knowledge_document ADD CONSTRAINT fk_document_kb FOREIGN KEY (kb_id) REFERENCES knowledge_base(id);
ALTER TABLE document_version ADD CONSTRAINT fk_version_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id);
ALTER TABLE document_chunk_ref ADD CONSTRAINT fk_chunk_version FOREIGN KEY (version_id) REFERENCES document_version(id);
ALTER TABLE conversation ADD CONSTRAINT fk_conversation_user FOREIGN KEY (user_id) REFERENCES app_user(id);
ALTER TABLE chat_message ADD CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES conversation(id);
ALTER TABLE message_feedback ADD CONSTRAINT fk_feedback_message FOREIGN KEY (message_id) REFERENCES chat_message(id);
ALTER TABLE gap_occurrence ADD CONSTRAINT fk_occurrence_gap FOREIGN KEY (gap_id) REFERENCES knowledge_gap(id);
ALTER TABLE gap_occurrence ADD CONSTRAINT fk_occurrence_feedback FOREIGN KEY (feedback_id) REFERENCES message_feedback(id);
ALTER TABLE gap_verification ADD CONSTRAINT fk_verification_gap FOREIGN KEY (gap_id) REFERENCES knowledge_gap(id);

-- Verify after migration.
SHOW CREATE TABLE knowledge_gap;
SHOW CREATE TABLE gap_occurrence;
SHOW CREATE TABLE gap_verification;