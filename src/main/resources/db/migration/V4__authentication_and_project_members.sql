CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9_-]{2,39}$'),
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE project_member (
    project_id UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES app_user(id),
    role VARCHAR(10) NOT NULL CHECK (role IN ('OWNER','EDITOR','VIEWER')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(project_id,user_id)
);
CREATE UNIQUE INDEX one_project_owner ON project_member(project_id) WHERE role='OWNER';
CREATE INDEX member_projects ON project_member(user_id,project_id);
ALTER TABLE source_document ADD COLUMN created_by UUID REFERENCES app_user(id);
ALTER TABLE change_proposal ADD COLUMN reviewed_by UUID REFERENCES app_user(id);
ALTER TABLE task_change ADD COLUMN actor_user_id UUID REFERENCES app_user(id);
