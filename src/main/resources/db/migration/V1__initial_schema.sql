CREATE TABLE project (id UUID PRIMARY KEY,name VARCHAR(120) NOT NULL,timezone VARCHAR(60) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE source_document (
 id UUID PRIMARY KEY,project_id UUID NOT NULL REFERENCES project(id),title VARCHAR(200) NOT NULL,
 content TEXT NOT NULL CHECK(char_length(content) BETWEEN 1 AND 10000),content_hash CHAR(64) NOT NULL,
 reference_date DATE NOT NULL,timezone VARCHAR(60) NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(project_id,content_hash,reference_date,timezone));
CREATE TABLE extraction_job (
 id UUID PRIMARY KEY,document_id UUID NOT NULL REFERENCES source_document(id),
 status VARCHAR(20) NOT NULL CHECK(status IN ('QUEUED','RUNNING','SUCCEEDED','RETRY_WAIT','FAILED')),
 attempt_count INT NOT NULL DEFAULT 0,next_run_at TIMESTAMPTZ,lease_until TIMESTAMPTZ,lease_token UUID,
 model VARCHAR(80) NOT NULL,prompt_version VARCHAR(40) NOT NULL,input_snapshot_json JSONB NOT NULL,
 error_code VARCHAR(80),created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE task (
 id UUID PRIMARY KEY,project_id UUID NOT NULL REFERENCES project(id),title VARCHAR(200) NOT NULL,
 assignee_name VARCHAR(120),due_date DATE,status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','IN_PROGRESS','DONE','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0 CHECK(version>=0),archived_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),UNIQUE(id,project_id));
CREATE TABLE change_proposal (
 id UUID PRIMARY KEY,job_id UUID NOT NULL REFERENCES extraction_job(id),project_id UUID NOT NULL REFERENCES project(id),
 ordinal INT NOT NULL,operation VARCHAR(20) NOT NULL CHECK(operation IN ('CREATE_TASK','UPDATE_TASK')),
 target_task_id UUID,base_task_version BIGINT,candidate_json JSONB NOT NULL,evidence_json JSONB NOT NULL,reviewed_json JSONB,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPLIED','REJECTED')),
 applied_task_id UUID,created_at TIMESTAMPTZ NOT NULL DEFAULT now(),reviewed_at TIMESTAMPTZ,
 UNIQUE(job_id,ordinal),FOREIGN KEY(target_task_id,project_id) REFERENCES task(id,project_id),
 FOREIGN KEY(applied_task_id,project_id) REFERENCES task(id,project_id));
CREATE TABLE task_change (
 id UUID PRIMARY KEY,task_id UUID NOT NULL REFERENCES task(id),proposal_id UUID UNIQUE REFERENCES change_proposal(id),
 actor_type VARCHAR(20) NOT NULL CHECK(actor_type IN ('REVIEWER','MANUAL')),before_json JSONB,after_json JSONB NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX document_project_idx ON source_document(project_id,created_at);
CREATE INDEX job_document_idx ON extraction_job(document_id,created_at);
CREATE INDEX task_project_idx ON task(project_id,created_at,id);
CREATE INDEX proposal_job_idx ON change_proposal(job_id,ordinal);
CREATE INDEX history_task_idx ON task_change(task_id,created_at,id);
