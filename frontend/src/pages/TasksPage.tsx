import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, ClipboardList, Clock3, FileText, History as HistoryIcon, LoaderCircle, PencilLine, Search, X } from 'lucide-react';
import { ApiError, api } from '../api';
import { useWorkspace } from '../App';
import { Badge, date, Empty, ErrorPanel, Heading, Loading, Pagination, statusLabels, time, today } from '../components';
import type { History, Task, TaskStatus } from '../types';

function TaskEditor({ task, onClose }: { task: Task; onClose: () => void }) {
  const { notify, refresh } = useWorkspace();
  const cache = useQueryClient();
  const [title, setTitle] = useState(task.title);
  const [owner, setOwner] = useState(task.assignee_name || '');
  const [due, setDue] = useState(task.due_date || '');
  const [status, setStatus] = useState<TaskStatus>(task.status);
  const [version, setVersion] = useState(task.version);
  const [conflict, setConflict] = useState(false);
  const mutation = useMutation({
    mutationFn: () => api.editTask(task.id, { title: title.trim(), assigneeName: owner.trim() || null, dueDate: due || null }, status, version),
    onSuccess: async () => { await Promise.all([refresh(), cache.invalidateQueries({ queryKey: ['task', task.id] }), cache.invalidateQueries({ queryKey: ['history', task.id] })]); notify('任务已保存，变更已写入历史。'); onClose(); },
    onError: async error => { if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') { setConflict(true); await cache.invalidateQueries({ queryKey: ['task', task.id] }); } },
  });
  const submit = (event: FormEvent) => { event.preventDefault(); if (!conflict && title.trim() && !mutation.isPending) mutation.mutate(); };
  return <form className="task-edit" onSubmit={submit}><div className="section-heading"><h3>编辑当前任务</h3><button type="button" className="icon-button" aria-label="取消编辑" onClick={onClose} disabled={mutation.isPending}><X size={16}/></button></div>
    {conflict ? <div className="conflict-panel" role="alert"><strong>任务已被修改，你的输入仍保留</strong><p>当前任务：{task.title} · {task.assignee_name || '未分配'} · {date(task.due_date)} · {statusLabels[task.status]}，版本 v{task.version}。核对后再保存。</p><button type="button" className="button small secondary" onClick={() => { setVersion(task.version); setConflict(false); mutation.reset(); }}>已核对，使用最新版本</button></div> : null}
    <label htmlFor="edit-task-title">任务标题</label><input id="edit-task-title" value={title} onChange={event => setTitle(event.target.value)} maxLength={200} required disabled={mutation.isPending}/>
    <div className="form-row"><div><label htmlFor="edit-task-owner">负责人</label><input id="edit-task-owner" value={owner} onChange={event => setOwner(event.target.value)} maxLength={120} disabled={mutation.isPending}/></div><div><label htmlFor="edit-task-date">截止日期</label><input id="edit-task-date" type="date" value={due} onChange={event => setDue(event.target.value)} disabled={mutation.isPending}/></div></div>
    <label htmlFor="edit-task-status">任务状态</label><select id="edit-task-status" value={status} onChange={event => setStatus(event.target.value as TaskStatus)} disabled={mutation.isPending}>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
    {mutation.isError && !conflict ? <ErrorPanel error={mutation.error}/> : null}
    <div className="review-actions"><button type="button" className="button secondary" onClick={onClose} disabled={mutation.isPending}>取消</button><button className="button primary" disabled={mutation.isPending || conflict || !title.trim()}>{mutation.isPending ? <LoaderCircle className="spin" size={16}/> : <Check size={16}/>}保存任务</button></div>
  </form>;
}
function Changes({ entry }: { entry: History }) {
  const before = entry.before_json, after = entry.after_json;
  const fields: { label: string; before: string | null | undefined; after: string | null | undefined }[] = [
    { label: '标题', before: before?.title, after: after.title },
    { label: '负责人', before: before?.assignee_name, after: after.assignee_name },
    { label: '截止日期', before: before?.due_date, after: after.due_date },
    { label: '状态', before: before ? statusLabels[before.status] : undefined, after: statusLabels[after.status] },
  ];
  const changed = fields.filter(field => !before || field.before !== field.after);
  return <div className="history-changes">{changed.length ? changed.map(field => <div key={field.label}><span>{field.label}</span>{before ? <><del>{date(field.before)}</del><ArrowRight size={12}/></> : null}<strong>{date(field.after)}</strong></div>) : <p className="subtle">字段值未变化，保留本次确认记录。</p>}</div>;
}
function TaskDetail({ id }: { id: string }) {
  const { project } = useWorkspace();
  const navigate = useNavigate();
  const task = useQuery({ queryKey: ['task', id], queryFn: () => api.task(id) });
  const history = useQuery({ queryKey: ['history', id], queryFn: () => api.history(id) });
  const [editing, setEditing] = useState(false);
  if (task.isPending) return <Loading text="正在读取任务与历史…"/>;
  if (task.isError) return <ErrorPanel error={task.error} retry={() => { void task.refetch(); }}/>;
  if (task.data.project_id !== project!.id) return <Empty title="任务不属于当前项目" description="请从左侧列表选择本项目的任务。"/>;
  const item = task.data;
  const prefill = '更新任务：' + item.id + '；负责人：' + (item.assignee_name || '') + '；截止：' + (item.due_date || today(project!.timezone));
  return <><div className="task-detail-header"><div className="label-row"><Badge tone={item.status === 'DONE' ? 'green' : item.status === 'IN_PROGRESS' ? 'amber' : 'neutral'}>{statusLabels[item.status]}</Badge><span className="version-chip">版本 v{item.version}</span></div><h2>{item.title}</h2><div className="task-facts"><span><small>负责人</small><b>{item.assignee_name || '未分配'}</b></span><span><small>截止日期</small><b>{date(item.due_date)}</b></span><span><small>最近更新</small><b>{time(item.updated_at, project!.timezone)}</b></span></div><div className="task-detail-actions"><button className="button small secondary" onClick={() => setEditing(true)} disabled={editing}><PencilLine size={14}/>编辑任务</button><button className="button small secondary" onClick={() => navigate('/projects/' + project!.id + '/import', { state: { prefill } })}><FileText size={14}/>生成更新记录</button></div></div>
    {editing ? <TaskEditor task={item} onClose={() => setEditing(false)}/> : null}
    <section className="timeline-section"><div className="section-heading"><h3><HistoryIcon size={17}/>变更时间线</h3><span className="subtle">{history.data?.length || 0} 次记录</span></div>{history.isPending ? <Loading/> : history.isError ? <ErrorPanel error={history.error} retry={() => { void history.refetch(); }}/> : <div className="timeline">{[...history.data].sort((a, b) => b.after_json.version - a.after_json.version).map(entry => <article className="timeline-entry" key={entry.id}><span className={'timeline-dot ' + (entry.actor_type === 'MANUAL' ? 'manual' : '')}>{entry.actor_type === 'MANUAL' ? <PencilLine size={12}/> : <Check size={12}/>}</span><div className="timeline-meta"><strong>{!entry.before_json ? '从已确认的建议建立任务' : entry.actor_type === 'MANUAL' ? '人工更新任务' : '确认变更建议'}</strong><time>{time(entry.created_at, project!.timezone)}</time></div><Changes entry={entry}/>{entry.evidence_json ? <div className="history-evidence"><span><FileText size={13}/>来源证据</span><blockquote>{entry.evidence_json.quote}</blockquote>{entry.document_id ? <Link to={'/projects/' + project!.id + '/review/' + entry.document_id}>回看原始记录<ArrowRight size={13}/></Link> : null}</div> : <p className="field-hint">由人工直接编辑，已保留前后字段值。</p>}</article>)}</div>}</section></>;
}
function TasksWorkspace() {
  const { project } = useWorkspace();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [params, setParams] = useSearchParams();
  const tasks = useQuery({ queryKey: ['tasks', project!.id, page], queryFn: () => api.tasks(project!.id, page) });
  const selected = params.get('task') || tasks.data?.[0]?.id;
  const filtered = tasks.data?.filter(task => (!status || task.status === status) && (!search.trim() || (task.title + ' ' + (task.assignee_name || '')).toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))) || [];
  return <>
    <Heading eyebrow="EVERY CHANGE HAS A STORY" title="任务历史" description="看到当前的行动，也能回到它最初的依据。" action={<Link className="button primary" to={'/projects/' + project!.id + '/import'}>导入新的记录<ArrowRight size={16}/></Link>}/>
    <div className="tasks-grid"><section className="panel task-list-panel"><div className="section-heading"><h2>项目任务 <span>{project!.task_count}</span></h2><ClipboardList size={19} className="muted"/></div><div className="task-filters"><div className="search-input"><Search size={16}/><input aria-label="筛选本页任务" placeholder="筛选本页标题或负责人" value={search} onChange={event => setSearch(event.target.value)}/></div><select aria-label="本页任务状态" value={status} onChange={event => setStatus(event.target.value)}><option value="">全部状态</option>{Object.entries(statusLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></div>
    {tasks.isPending ? <Loading/> : tasks.isError ? <ErrorPanel error={tasks.error} retry={() => { void tasks.refetch(); }}/> : tasks.data.length ? <><div className="task-list">{filtered.map(task => <button key={task.id} data-task-id={task.id} className={'task-row ' + (selected === task.id ? 'selected' : '')} onClick={() => setParams({ task: task.id })} aria-pressed={selected === task.id}><span className={'task-state ' + task.status.toLowerCase()}>{task.status === 'DONE' ? <Check size={13}/> : <span/>}</span><div><strong>{task.title}</strong><span>{task.assignee_name || '未分配'}<i>·</i><Clock3 size={12}/>{date(task.due_date)}</span></div><Badge tone={task.status === 'DONE' ? 'green' : task.status === 'IN_PROGRESS' ? 'amber' : 'neutral'}>{statusLabels[task.status]}</Badge></button>)}</div>{!filtered.length ? <Empty title="本页没有匹配的任务" description="调整筛选条件，或查看其他分页。"/> : null}<Pagination page={page} count={tasks.data.length} onChange={value => { setPage(value); setParams({}); }}/></> : <Empty title="还没有建立任务" description="从已导入的记录中确认一条建议，任务就会显示在这里。" action={<Link className="button secondary" to={'/projects/' + project!.id + '/review'}>去审核建议<ArrowRight size={14}/></Link>}/>}</section>
    <section className="panel task-history-panel">{selected ? <TaskDetail key={selected} id={selected}/> : <Empty title="每个任务都有一条时间线" description="建立或选择任务后，在这里查看字段变更、原文和人工确认记录。"/>}</section></div>
  </>;
}
export function TasksPage() { const { project } = useWorkspace(); return <TasksWorkspace key={project!.id}/>; }
