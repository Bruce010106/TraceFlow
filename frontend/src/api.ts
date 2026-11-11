import type { User, Member, Project, Task, TaskFields, TaskStatus, SourceDocument, DocumentSummary, History, Health } from './types';
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); this.name = 'ApiError'; }
}
let csrf: Promise<{ token: string; headerName: string }> | undefined;
let authEpoch = 0;
function csrfToken() { return csrf ??= request<{ token: string; headerName: string }>('/auth/csrf').catch(error => { csrf = undefined; throw error; }); }
async function request<T>(path: string, options: RequestInit = {}, timeoutMs = 20000): Promise<T> {
  const requestEpoch = authEpoch;
  try {
    const token = options.method && options.method !== 'GET' ? await csrfToken() : null;
    const response = await fetch('/api' + path, { ...options, credentials: 'same-origin', signal: options.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(timeoutMs)]) : AbortSignal.timeout(timeoutMs), headers: { 'Content-Type': 'application/json', ...(token ? { [token.headerName]: token.token } : {}), ...options.headers } });
    const body = await response.json().catch(() => null);
    if (response.status === 401 && requestEpoch === authEpoch) { csrf = undefined; if (!['/auth/login', '/auth/register'].includes(path)) window.dispatchEvent(new Event('traceflow:unauthenticated')); }
    if (body?.code === 'CSRF_INVALID') csrf = undefined;
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
  me: () => request<User>('/auth/me'),
  register: (username: string, password: string) => request<User>('/auth/register', post({ username, password })),
  login: async (username: string, password: string) => { authEpoch++; const user = await request<User>('/auth/login', post({ username, password })); csrf = undefined; return user; },
  logout: async () => { await request('/auth/logout', post()); authEpoch++; csrf = undefined; },
  members: (project: string) => request<Member[]>('/projects/' + project + '/members'),
  setMember: (project: string, username: string, role: 'EDITOR' | 'VIEWER') => request<Member[]>('/projects/' + project + '/members/' + encodeURIComponent(username), { method: 'PUT', body: JSON.stringify({ role }) }),
  removeMember: (project: string, username: string) => request<Member[]>('/projects/' + project + '/members/' + encodeURIComponent(username), { method: 'DELETE' }),
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
