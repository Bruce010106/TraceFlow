ALTER TABLE extraction_job
 ADD COLUMN generation INT NOT NULL DEFAULT 1,
 ADD COLUMN max_attempts INT NOT NULL DEFAULT 3,
 ADD COLUMN worker_id VARCHAR(100),
 ADD COLUMN started_at TIMESTAMPTZ,
 ADD COLUMN last_heartbeat_at TIMESTAMPTZ;

-- P3 used one job per manual attempt. Preserve that order as generation.
UPDATE extraction_job SET generation=GREATEST(attempt_count,1);
CREATE TABLE extraction_attempt (
 id UUID PRIMARY KEY,
 job_id UUID NOT NULL REFERENCES extraction_job(id) ON DELETE CASCADE,
 attempt_no INT NOT NULL CHECK(attempt_no>0),
 lease_token UUID UNIQUE,
 worker_id VARCHAR(100) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED','EXPIRED','ABANDONED')),
 model VARCHAR(80) NOT NULL,
 prompt_version VARCHAR(40) NOT NULL,
 resolved_model VARCHAR(80),
 response_id VARCHAR(200),
 input_tokens INT CHECK(input_tokens>=0),
 output_tokens INT CHECK(output_tokens>=0),
 error_code VARCHAR(80),
 started_at TIMESTAMPTZ NOT NULL,
 completed_at TIMESTAMPTZ,
 duration_ms BIGINT CHECK(duration_ms>=0),
 UNIQUE(job_id,attempt_no)
);
INSERT INTO extraction_attempt(id,job_id,attempt_no,worker_id,status,model,prompt_version,
 resolved_model,response_id,input_tokens,output_tokens,error_code,started_at,completed_at,duration_ms)
 SELECT id,id,1,'legacy-p3',status,model,prompt_version,resolved_model,response_id,input_tokens,output_tokens,
 error_code,created_at,COALESCE(completed_at,created_at),duration_ms
 FROM extraction_job WHERE status IN ('SUCCEEDED','FAILED');

-- Stop P3 processes before migration. Interrupted P3 work is safely re-enqueued.
UPDATE extraction_job SET
 attempt_count=CASE WHEN status IN ('SUCCEEDED','FAILED') THEN 1 ELSE 0 END,
 status=CASE WHEN status IN ('RUNNING','QUEUED','RETRY_WAIT') THEN 'QUEUED' ELSE status END,
 next_run_at=CASE WHEN status IN ('RUNNING','QUEUED','RETRY_WAIT') THEN clock_timestamp() ELSE NULL END,
 lease_until=NULL,lease_token=NULL,worker_id=NULL;

DROP INDEX extraction_document_running_idx;
CREATE UNIQUE INDEX extraction_document_active_idx ON extraction_job(document_id)
 WHERE status IN ('QUEUED','RUNNING','RETRY_WAIT');
CREATE UNIQUE INDEX extraction_document_generation_idx ON extraction_job(document_id,generation);
CREATE INDEX extraction_queue_ready_idx ON extraction_job(next_run_at,created_at,id)
 WHERE status IN ('QUEUED','RETRY_WAIT');
CREATE INDEX extraction_queue_expired_idx ON extraction_job(lease_until,id) WHERE status='RUNNING';
ALTER TABLE extraction_job
 ADD CONSTRAINT extraction_attempt_bounds CHECK(attempt_count>=0 AND max_attempts BETWEEN 1 AND 10 AND attempt_count<=max_attempts),
 ADD CONSTRAINT extraction_generation_positive CHECK(generation>0),
 ADD CONSTRAINT extraction_lease_state CHECK(
  (status='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND worker_id IS NOT NULL)
  OR (status<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL AND worker_id IS NULL)),
 ADD CONSTRAINT extraction_ready_time CHECK(status NOT IN ('QUEUED','RETRY_WAIT') OR next_run_at IS NOT NULL);
