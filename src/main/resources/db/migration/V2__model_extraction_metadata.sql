ALTER TABLE extraction_job
 ADD COLUMN provider VARCHAR(40) NOT NULL DEFAULT 'legacy',
 ADD COLUMN resolved_model VARCHAR(80),
 ADD COLUMN response_id VARCHAR(200),
 ADD COLUMN input_tokens INT CHECK(input_tokens >= 0),
 ADD COLUMN output_tokens INT CHECK(output_tokens >= 0),
 ADD COLUMN duration_ms BIGINT CHECK(duration_ms >= 0),
 ADD COLUMN completed_at TIMESTAMPTZ,
 ADD COLUMN output_json JSONB;
CREATE UNIQUE INDEX extraction_document_running_idx ON extraction_job(document_id) WHERE status='RUNNING';
CREATE UNIQUE INDEX extraction_document_success_idx ON extraction_job(document_id) WHERE status='SUCCEEDED';
