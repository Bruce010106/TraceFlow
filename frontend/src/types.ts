export interface User { id: string; username: string }
export type ProjectRole = 'OWNER' | 'EDITOR' | 'VIEWER';
export interface Member extends User { role: ProjectRole }
export interface Health { status: string; extractor: 'llm' | 'mock'; provider: string; model: string; configured: boolean; promptVersion: string; queueEnabled: boolean }
export interface Project { role: ProjectRole; id: string; name: string; timezone: string; created_at: string; document_count: number; task_count: number; pending_count: number; active_job_count: number }
export interface Task { id: string; project_id: string; title: string; assignee_name: string | null; due_date: string | null; status: TaskStatus; version: number; created_at: string; updated_at: string }
export type TaskStatus = 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED';
export interface TaskFields { title: string; assigneeName: string | null; dueDate: string | null }
export interface Evidence { quote: string; blockId: string; fields: string[]; source: string }
export interface Proposal { id: string; operation: 'CREATE_TASK' | 'UPDATE_TASK'; target_task_id: string | null; base_task_version: number | null; candidate_json: TaskFields; evidence_json: Evidence; reviewed_json: (TaskFields & { expectedTaskVersion?: number }) | null; status: 'PENDING' | 'APPLIED' | 'REJECTED'; applied_task_id: string | null }
export type ExtractionState = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'RETRY_WAIT' | 'FAILED';
export interface ExtractionAttempt { attempt_no: number; status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'ABANDONED'; error_code: string | null; started_at: string; completed_at: string | null }
export interface ExtractionJob { id: string; status: ExtractionState; provider: string; model: string; resolved_model: string | null; prompt_version: string; attempt_count: number; generation: number; max_attempts: number; next_run_at: string | null; lease_until: string | null; attempts: ExtractionAttempt[]; error_code: string | null; duration_ms: number | null; input_tokens: number | null; output_tokens: number | null; input_snapshot_json: { tasks: Pick<Task, 'id' | 'title' | 'assignee_name' | 'due_date' | 'status' | 'version'>[] } }
export interface DocumentSummary { id: string; project_id: string; title: string; reference_date: string; created_at: string; proposal_count: number; pending_count: number; extraction_status: ExtractionState; extraction_error: string | null }
export interface SourceDocument extends Omit<DocumentSummary, 'proposal_count' | 'pending_count' | 'extraction_status' | 'extraction_error'> { duplicate?: boolean; content: string; timezone: string; proposals: Proposal[]; jobs: ExtractionJob[] }
export interface History { actor_username: string | null; id: string; actor_type: string; before_json: Task | null; after_json: Task; document_id: string | null; evidence_json: Evidence | null; created_at: string }
