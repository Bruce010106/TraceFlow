import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ArrowRight, Check, CheckCircle2, Copy, FileText, History, LoaderCircle, RefreshCw, ShieldCheck, X } from 'lucide-react';
import { ApiError, api } from '../api';
import { DocumentBadge, ExtractionStatus, isExtractionActive } from '../extraction';
import { useWorkspace } from '../App';
import { Badge, date, Empty, ErrorPanel, Heading, Loading, Pagination, time } from '../components';
import type { Proposal, SourceDocument, TaskFields } from '../types';

function ReviewInbox() {
  const { project } = useWorkspace();
  const [page, setPage] = useState(0);
  const docs = useQuery({ queryKey: ['documents', project!.id, page], queryFn: () => api.documents(project!.id, page), refetchInterval: query => query.state.data?.some(doc => isExtractionActive(doc.extraction_status)) ? 1500 : false });
  return <>
    <Heading eyebrow="REVIEW WITH CONTEXT" title="建议审核" description="先看证据，再做决定。选择一份记录，继续核对它的任务建议。" action={<Link className="button primary" to={'/projects/' + project!.id + '/import'}>导入新文档<ArrowRight size={16}/></Link>}/>
    <div className="review-intro"><span className="panel-symbol"><ShieldCheck size={25}/></span><div><h2>{project!.pending_count ? project!.pending_count + ' 条建议，等待你的确认' : '每条变更，都由你确认'}</h2><p>文档是依据，建议是起点。未经确认的内容，不会改动任务。</p></div></div>
    <section className="panel"><div className="section-heading"><h2>选择一份文档</h2><span className="subtle">原文与审核结果会一直保留</span></div>{docs.isPending ? <Loading/> : docs.isError ? <ErrorPanel error={docs.error} retry={() => { void docs.refetch(); }}/> : docs.data.length ? <><div className="document-list">{docs.data.map(doc => <Link key={doc.id} className="document-row" to={'/projects/' + project!.id + '/review/' + doc.id}><span className="document-icon"><FileText size={20}/></span><div><strong>{doc.title}</strong><span>{doc.reference_date} · {doc.proposal_count} 条建议</span></div><DocumentBadge document={doc}/><ArrowRight size={17}/></Link>)}</div><Pagination page={page} count={docs.data.length} onChange={setPage}/></> : <Empty title="还没有可审核的记录" description="先导入一份项目记录，这里就会出现附有原文的建议。" action={<Link className="button secondary" to={'/projects/' + project!.id + '/import'}>去导入文档<ArrowRight size={15}/></Link>}/>}</section>
  </>;
}
function ReviewEditor({ proposal, document, onRefresh }: { proposal: Proposal; document: SourceDocument; onRefresh: () => Promise<void> }) {
  const { project, notify, refresh } = useWorkspace();
  const cache = useQueryClient();
  const [title, setTitle] = useState(proposal.candidate_json.title);
  const [owner, setOwner] = useState(proposal.candidate_json.assigneeName || '');
  const [due, setDue] = useState(proposal.candidate_json.dueDate || '');
  const [version, setVersion] = useState<number | undefined>(proposal.base_task_version ?? undefined);
  const [conflict, setConflict] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [reloading, setReloading] = useState(false);
  const [reloadError, setReloadError] = useState<unknown>(null);
  const guard = useRef(false);
  const isUpdate = proposal.operation === 'UPDATE_TASK';
  const current = useQuery({ queryKey: ['task', proposal.target_task_id], queryFn: () => api.task(proposal.target_task_id!), enabled: isUpdate && !!proposal.target_task_id });
  const fields: TaskFields = { title: title.trim(), assigneeName: owner.trim() || null, dueDate: due || null };
  const refreshAfterWrite = async () => {
    await Promise.all([onRefresh(), refresh(), cache.invalidateQueries({ queryKey: ['task'] }), cache.invalidateQueries({ queryKey: ['history'] })]);
  };
  async function loadLatest() {
    if (!proposal.target_task_id) return;
    setReloading(true); setReloadError(null);
    try { cache.setQueryData(['task', proposal.target_task_id], await api.task(proposal.target_task_id)); }
    catch (error) { setReloadError(error); }
    finally { setReloading(false); }
  }
  const approve = useMutation({
    mutationFn: () => api.approve(proposal.id, fields, isUpdate ? version : undefined),
    onSuccess: async () => { notify(isUpdate ? '建议已确认，任务与变更历史已更新。' : '建议已确认，已建立任务及来源记录。'); await refreshAfterWrite(); },
    onError: async error => { if (error instanceof ApiError && error.code === 'VERSION_CONFLICT') { setConflict(true); await loadLatest(); } },
  });
  const reject = useMutation({ mutationFn: () => api.reject(proposal.id), onSuccess: async () => { notify('建议已拒绝，原文仍完整保留。'); await refreshAfterWrite(); } });
  const applied = proposal.status === 'APPLIED' || approve.isSuccess;
  const rejected = proposal.status === 'REJECTED' || reject.isSuccess;
  const busy = approve.isPending || reject.isPending;
  const changedVersion = isUpdate && current.data && version !== current.data.version;
  const needAck = !!changedVersion || conflict;
  const changedDraft = fields.title !== proposal.candidate_json.title || fields.assigneeName !== (proposal.candidate_json.assigneeName || null) || fields.dueDate !== (proposal.candidate_json.dueDate || null);
  const base = document.jobs.flatMap(job => job.input_snapshot_json.tasks).find(task => task.id === proposal.target_task_id);
  const currentValues = current.data;
  const rows = [
    { name: '任务标题', before: currentValues?.title, after: title || '未填写' },
    { name: '负责人', before: currentValues?.assignee_name, after: owner || null },
    { name: '截止日期', before: currentValues?.due_date, after: due || null },
  ];
  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!title.trim() || busy || guard.current || needAck || (isUpdate && !current.data)) return;
    guard.current = true;
    approve.mutate(undefined, { onSettled: () => { guard.current = false; } });
  };
  if (applied || rejected) return <div className="review-receipt"><span className={'receipt-symbol ' + (rejected ? 'muted-symbol' : '')}>{applied ? <CheckCircle2 size={32}/> : <X size={30}/>}</span><Badge tone={applied ? 'green' : 'neutral'}>{applied ? '已确认' : '已拒绝'}</Badge><h2>{applied ? '这条变更已记录' : '这条建议已拒绝'}</h2><p>{applied ? '任务已更新，原始建议、人工修订和来源证据均已保留。' : '任务没有发生变化。你仍可以在左侧查阅原始记录。'}</p>{applied ? <Link className="button primary" to={'/projects/' + project!.id + '/tasks?task=' + (approve.data?.id || proposal.applied_task_id)}>查看任务历史<History size={16}/></Link> : null}</div>;
  return <form className="review-form" onSubmit={submit}>
    <div className="section-heading"><div><Badge tone={isUpdate ? 'amber' : 'green'}>{isUpdate ? '修改已有任务' : '新建任务'}</Badge><h2>{isUpdate ? '核对这次变更' : '确认这条新任务'}</h2></div>{changedDraft ? <Badge tone="amber">已人工修订</Badge> : null}</div>
    <div className="evidence-note"><ShieldCheck size={17}/><span>先核对原文，你可以修改建议中的字段。</span></div>
    {isUpdate && current.isPending ? <Loading text="正在读取当前任务…"/> : null}
    {isUpdate && current.isError ? <ErrorPanel error={current.error} retry={() => { void current.refetch(); }}/> : null}
    {isUpdate && current.data ? <><div className="version-note">抽取时版本 <b>v{base?.version ?? proposal.base_task_version}</b><span>→</span>当前版本 <b>v{current.data.version}</b></div><div className="diff-table"><div className="diff-head"><span>字段</span><span>任务当前值</span><span>本次确认值</span></div>{rows.map(row => <div key={row.name} className={(row.before || '') !== (row.after || '') ? 'is-changed' : ''}><span>{row.name}</span><span>{date(row.before)}</span><strong>{date(row.after)}</strong></div>)}</div></> : null}
    {needAck ? <div className="conflict-panel" role="alert"><strong>任务版本已变化，请重新核对</strong><p>你的填写内容已保留。对照最新任务值，确认后再提交；系统不会自动覆盖。</p><div><button type="button" className="button small secondary" onClick={() => { void loadLatest(); }} disabled={reloading}>{reloading ? <LoaderCircle className="spin" size={14}/> : <RefreshCw size={14}/>}刷新当前任务</button><button type="button" className="button small primary" disabled={reloading || !current.data || !!reloadError} onClick={() => { setVersion(current.data!.version); setConflict(false); approve.reset(); }}>已核对，使用最新版本</button></div></div> : null}
    {reloadError ? <ErrorPanel error={reloadError}/> : null}
    <label htmlFor="review-title">任务标题</label><input id="review-title" value={title} onChange={event => setTitle(event.target.value)} maxLength={200} required disabled={busy}/>
    <div className="form-row"><div><label htmlFor="review-owner">负责人</label><input id="review-owner" value={owner} onChange={event => setOwner(event.target.value)} maxLength={120} placeholder="未确定可留空" disabled={busy}/></div><div><label htmlFor="review-date">截止日期</label><input id="review-date" type="date" value={due} onChange={event => setDue(event.target.value)} disabled={busy}/></div></div>
    {approve.isError && !(approve.error instanceof ApiError && approve.error.code === 'VERSION_CONFLICT') ? <ErrorPanel error={approve.error}/> : null}
    {reject.isError ? <ErrorPanel error={reject.error}/> : null}
    {rejecting ? <div className="reject-confirm"><p>拒绝后，这条建议将结束审核，任务不会改变。</p><button type="button" className="button small danger" disabled={busy} onClick={() => reject.mutate()}>确认拒绝</button><button type="button" className="button small secondary" onClick={() => setRejecting(false)} disabled={busy}>继续核对</button></div> : null}
    <div className="review-actions"><button type="button" className="button secondary" onClick={() => setRejecting(true)} disabled={busy || rejecting}><X size={16}/>拒绝建议</button><button className="button primary" disabled={busy || !title.trim() || needAck || (isUpdate && !current.data)}>{approve.isPending ? <LoaderCircle className="spin" size={16}/> : <Check size={16}/>}确认并{isUpdate ? '更新' : '创建'}任务</button></div>
    <p className="field-hint">确认操作会同时保存任务与历史。重复确认同一条建议，不会重复创建任务。</p>
  </form>;
}
function DocumentReview({ id }: { id: string }) {
  const { project, notify, refresh: refreshWorkspace } = useWorkspace();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const [selectedId, setSelectedId] = useState('');
  const query = useQuery({ queryKey: ['document', id], queryFn: () => api.document(id), refetchInterval: query => ['RUNNING', 'QUEUED', 'RETRY_WAIT'].includes(query.state.data?.jobs.at(-1)?.status || '') ? 1500 : false });
  const retry = useMutation({ mutationFn: () => api.retryExtraction(id), onSuccess: async doc => { cache.setQueryData(['document', id], doc); await refreshWorkspace(); notify(doc.jobs.at(-1)?.status === 'FAILED' ? '抽取仍未完成，原文已保留。' : doc.jobs.at(-1)?.status === 'SUCCEEDED' ? '抽取已完成，请核对本次建议。' : '作业已加入后台队列，完成后会自动显示。'); } });
  const latestStatus = query.data?.jobs.at(-1)?.status;
  useEffect(() => { if (latestStatus === 'SUCCEEDED' || latestStatus === 'FAILED') { void cache.invalidateQueries({ queryKey: ['projects'] }); void cache.invalidateQueries({ queryKey: ['documents'] }); } }, [cache, id, latestStatus]);
  if (query.isPending) return <Loading text="正在打开原文与审核建议…"/>;
  if (query.isError) return <ErrorPanel error={query.error} retry={() => { void query.refetch(); }}/>;
  const document = query.data;
  if (document.project_id !== project!.id) return <Empty title="文档不属于当前项目" description="请从项目文档列表重新打开记录。" action={<Link className="button secondary" to={'/projects/' + project!.id + '/review'}>返回文档列表</Link>}/>;
  const selected = document.proposals.find(p => p.id === selectedId) || document.proposals.find(p => p.status === 'PENDING') || document.proposals[0];
  const job = document.jobs.at(-1);
  const running = retry.isPending || ['RUNNING', 'QUEUED', 'RETRY_WAIT'].includes(job?.status || '');
  const pending = document.proposals.filter(p => p.status === 'PENDING').length;
  const line = Number(selected?.evidence_json.blockId.replace('line-', ''));
  const refresh = async () => { if (selected) setSelectedId(selected.id); await query.refetch(); };
  return <>
    <Heading eyebrow="EVIDENCE BEFORE ACTION" title="建议审核" description="把原文放在手边，让每次确认都更有把握。" action={<Link className="button secondary" to={'/projects/' + project!.id + '/review'}><ArrowLeft size={15}/>全部文档</Link>}/>
    <div className="document-bar"><span className="document-icon"><FileText size={21}/></span><div><strong>{document.title}</strong><p>记录日期 {document.reference_date} · 导入于 {time(document.created_at, project!.timezone)}</p></div><Badge tone={job?.status === 'FAILED' ? 'red' : running || pending ? 'amber' : 'green'}>{job?.status === 'QUEUED' ? '已排队' : job?.status === 'RETRY_WAIT' ? '等待重试' : running ? '后台抽取中' : job?.status === 'FAILED' ? '抽取未完成' : pending ? pending + ' 条待审核' : document.proposals.length ? '审核已完成' : '无需审核'}</Badge></div>
    <ExtractionStatus job={job} onRetry={() => retry.mutate()} pending={retry.isPending} timezone={project!.timezone}/>{retry.isError ? <ErrorPanel error={retry.error}/> : null}
    <div className="review-grid"><section className="panel source-panel"><div className="section-heading"><h2>文档原文</h2><button className="icon-button" aria-label="复制原文" onClick={() => { void navigator.clipboard.writeText(document.content).then(() => notify('原文已复制。')).catch(() => notify('无法复制，请手动选择原文。')); }}><Copy size={16}/></button></div><div className="source-lines" aria-label="审核原文">{document.content.split('\n').map((text, index) => <div key={index} className={index + 1 === line ? 'highlight-line' : ''}><span className="line-number" aria-hidden="true">{(index + 1).toString().padStart(2, '0')}</span><span>{text || ' '}</span></div>)}</div>{selected ? <div className="source-citation"><Badge tone="green">原文依据 · 第 {line} 行</Badge><blockquote>{selected.evidence_json.quote}</blockquote><p>高亮内容对应当前选中的建议。</p></div> : null}</section>
    <section className="panel review-panel">{document.proposals.length ? <><div className="proposal-strip" aria-label="建议列表">{document.proposals.map((proposal, index) => <button key={proposal.id} className={'proposal-tab ' + (selected?.id === proposal.id ? 'selected' : '')} aria-pressed={selected?.id === proposal.id} onClick={() => setSelectedId(proposal.id)}><span>建议 {(index + 1).toString().padStart(2, '0')}</span><Badge tone={proposal.status === 'PENDING' ? 'amber' : proposal.status === 'APPLIED' ? 'green' : 'neutral'}>{proposal.status === 'PENDING' ? '待审核' : proposal.status === 'APPLIED' ? '已确认' : '已拒绝'}</Badge></button>)}</div><ReviewEditor key={selected.id} proposal={selected} document={document} onRefresh={refresh}/></> : <Empty title={running ? '正在抽取任务建议' : job?.status === 'FAILED' ? '本次没有生成可审核建议' : '这份记录没有任务建议'} description={running ? '后台处理会继续进行，页面会自动更新。' : job?.status === 'FAILED' ? '请先查看上方提示，完成重新抽取后再审核。' : '原文已保存。本次没有识别到明确的行动项，你可以补充记录后重新导入。'} action={<button className="button secondary" onClick={() => navigate('/projects/' + project!.id + '/import')}>返回文档导入<ArrowRight size={16}/></button>}/>}</section></div>
  </>;
}
export function ReviewPage() {
  const { documentId } = useParams();
  const { project } = useWorkspace();
  return documentId ? <DocumentReview key={documentId} id={documentId}/> : <ReviewInbox key={project!.id}/>;
}
