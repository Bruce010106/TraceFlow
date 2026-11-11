import type { Project, Task, TaskFields, TaskStatus, SourceDocument, DocumentSummary, History, Health } from './types';
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); this.name = 'ApiError'; }
}
async function request<T>(path: string, options: RequestInit = {}, timeoutMs = 20000): Promise<T> {
  try {
    const response = await fetch('/api' + path, { ...options, signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs), headers: { 'Content-Type': 'application/json', ...options.headers } });
    const body = await response.json().catch(() => null);
    if (!response.ok) throw new ApiError(response.status, body?.code || 'REQUEST_FAILED', body?.message || '服务暂不可用，请稍后重试。');
    return body as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    if (error instanceof DOMException && error.name === 'TimeoutError') throw new ApiError(0, 'REQUEST_TIMEOUT', '等待响应超时。写入可能已完成，请刷新项目文档确认结果后再操作。');
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请确认本机后端已启动后重试。');
  }
}
const post = (body?: unknown): RequestInit => ({ method: 'POST', ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
export const api = {
  health: () => request<Health>('/health'),
  projects: () => request<Project[]>('/projects'),
  createProject: (name: string, timezone: string) => request<Project>('/projects', post({ name, timezone })),
  documents: (project: string, page = 0) => request<DocumentSummary[]>('/projects/' + project + '/documents?page=' + page + '&size=20'),
  document: (id: string) => request<SourceDocument>('/documents/' + id),
  importDocument: (project: string, title: string, content: string, referenceDate: string) => request<SourceDocument>('/projects/' + project + '/documents', post({ title, content, referenceDate }), 105000),
  retryExtraction: (id: string) => request<SourceDocument>('/documents/' + id + '/extract', post(), 105000),
  approve: (id: string, fields: TaskFields, expectedTaskVersion?: number) => request<Task>('/proposals/' + id + '/approve', post({ ...fields, ...(expectedTaskVersion === undefined ? {} : { expectedTaskVersion }) })),
  reject: (id: string) => request<ProposalResponse>('/proposals/' + id + '/reject', post()),
  tasks: (project: string, page = 0) => request<Task[]>('/projects/' + project + '/tasks?page=' + page + '&size=20'),
  task: (id: string) => request<Task>('/tasks/' + id),
  editTask: (id: string, fields: TaskFields, status: TaskStatus, version: number) => request<Task>('/tasks/' + id, { method: 'PATCH', body: JSON.stringify({ ...fields, status, expectedTaskVersion: version }) }),
  history: (id: string) => request<History[]>('/tasks/' + id + '/history'),
};
interface ProposalResponse { id: string; status: string }
