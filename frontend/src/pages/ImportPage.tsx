import { useRef, useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { ArrowRight, FileText, LoaderCircle, Plus, UploadCloud, WandSparkles } from 'lucide-react';
import { api } from '../api';
import { DocumentBadge, isExtractionActive } from '../extraction';
import { useWorkspace } from '../App';
import { Badge, Empty, ErrorPanel, Heading, Loading, Pagination, time, today } from '../components';
function ImportForm() {
  const { project, refresh, notify } = useWorkspace();
  const health = useQuery({ queryKey: ['health'], queryFn: api.health });
  const isMock = health.data?.extractor === 'mock';
  const location = useLocation();
  const navigate = useNavigate();
  const state = location.state as { prefill?: string } | null;
  const [title, setTitle] = useState(state?.prefill ? '任务变更记录' : '');
  const [content, setContent] = useState(state?.prefill || '');
  const [referenceDate, setReferenceDate] = useState(today(project!.timezone));
  const [fileError, setFileError] = useState<string | null>(null);
  const [fileName, setFileName] = useState('');
  const [page, setPage] = useState(0);
  const reading = useRef(false);
  const [isReading, setIsReading] = useState(false);
  const documents = useQuery({ queryKey: ['documents', project!.id, page], queryFn: () => api.documents(project!.id, page), refetchInterval: query => query.state.data?.some(doc => isExtractionActive(doc.extraction_status)) ? 1500 : false });
  const mutation = useMutation({ mutationFn: () => api.importDocument(project!.id, title.trim(), content, referenceDate), onSuccess: async doc => { await refresh(); notify(doc.duplicate ? '这份文档已存在，已打开原有记录。' : doc.jobs.at(-1)?.status === 'FAILED' ? '原文已归档，抽取未完成。请查看详情。' : isExtractionActive(doc.jobs.at(-1)?.status) ? '原文已保存，作业已加入后台队列。' : doc.proposals.length ? '记录已归档，请核对抽取建议。' : '记录已归档，本次没有识别到任务建议。'); navigate('/projects/' + project!.id + '/review/' + doc.id); } });
  async function readFile(file?: File) {
    if (!file || reading.current) return;
    setFileError(null);
    if (!/\.(txt|md)$/i.test(file.name)) { setFileError('请选择 TXT 或 Markdown 文件。'); return; }
    if (file.size > 128000) { setFileError('文件过大，请使用不超过 10,000 字符的文本。'); return; }
    reading.current = true; setIsReading(true);
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer()).replace(/^\uFEFF/, '');
      if (!text.trim() || text.length > 10000) { setFileError('文档需包含 1–10,000 个字符。'); return; }
      setContent(text); setFileName(file.name);
      if (!title.trim()) setTitle(file.name.replace(/\.(txt|md)$/i, '').slice(0, 200));
    } catch { setFileError('无法读取文件，请将文件保存为 UTF-8 编码后重试。'); }
    finally { reading.current = false; setIsReading(false); }
  }
  const inputFile = (event: ChangeEvent<HTMLInputElement>) => { void readFile(event.target.files?.[0]); event.target.value = ''; };
  const sample = () => { setContent(old => (old ? old + '\n' : '') + (isMock ? '新增任务：接口联调；负责人：李明；截止：' : '请李明在 ' ) + referenceDate + (isMock ? '' : ' 前完成接口联调。')); if (!title) setTitle('项目启动记录'); };
  const submit = (event: FormEvent) => { event.preventDefault(); if (content.trim() && content.length <= 10000 && title.trim()) mutation.mutate(); };
  return <>
    <Heading eyebrow="CAPTURE THE CONTEXT" title="文档导入" description="留住沟通原文，让每条任务建议都有据可查。" action={<Badge tone="green">{project!.name}</Badge>}/>
    <div className="import-grid"><section className="panel import-editor"><div className="section-heading"><h2>一份新的项目记录</h2><span className="subtle">TXT / Markdown / 粘贴文本</span></div>
      <form onSubmit={submit}><div className="form-row"><div><label htmlFor="document-title">文档标题</label><input id="document-title" disabled={mutation.isPending} value={title} onChange={e => setTitle(e.target.value)} maxLength={200} required placeholder="例如：第一轮需求评审纪要"/></div><div><label htmlFor="reference-date">记录日期</label><input id="reference-date" disabled={mutation.isPending} type="date" value={referenceDate} onChange={e => setReferenceDate(e.target.value)} required/></div></div>
        <div className="upload-zone" onDragOver={e => e.preventDefault()} onDrop={e => { e.preventDefault(); if (!mutation.isPending) void readFile(e.dataTransfer.files[0]); }}><UploadCloud size={25}/><div><strong>{isReading ? '正在读取文件…' : fileName || '拖入一份记录，或从本机选择'}</strong><p>UTF-8 文本，最多 10,000 字符</p></div><label className="button small secondary upload-label" htmlFor="source-file">选择文件<input id="source-file" className="visually-hidden" type="file" accept=".txt,.md,text/plain,text/markdown" onChange={inputFile} disabled={isReading || mutation.isPending}/></label></div>
        {fileError ? <div className="inline-error" role="alert">{fileError}</div> : null}
        <div className="label-row"><label htmlFor="document-content">记录原文</label><button type="button" className="text-button" onClick={sample} disabled={mutation.isPending}><WandSparkles size={14}/>填入新增示例</button></div>
        <textarea id="document-content" disabled={mutation.isPending} className="source-textarea" value={content} onChange={e => { setContent(e.target.value); setFileName(''); }} maxLength={10000} required placeholder="粘贴会议记录或变更说明，也可以先填入一条示例。"/>
        <div className="editor-footer"><span>{content.length.toLocaleString()} / 10,000 字符</span><span>时区：{project!.timezone}</span></div>
        {mutation.isError ? <ErrorPanel error={mutation.error}/> : null}
        <div className="form-actions"><p className="subtle">导入后后台处理，离开页面也会继续。任务由你确认后更新。</p><button className="button primary" disabled={mutation.isPending || isReading || !content.trim() || !title.trim() || !referenceDate}>{mutation.isPending ? <LoaderCircle size={17} className="spin"/> : <Plus size={17}/>}导入并生成建议<ArrowRight size={16}/></button></div>
      </form></section>
      <aside className="import-help"><div className="help-card"><span className="panel-symbol"><WandSparkles size={22}/></span><h3>{isMock ? '先试一条离线示例' : '用你的话，留下行动项'}</h3><p>{isMock ? '当前为显式启用的离线示例模式，仅识别固定格式。' : '模型结合记录日期与当前项目任务分析原文，提取新增或变更建议。没有明确行动项时，只保留原文。'}</p><div className="sample-code">{isMock ? <>新增任务：接口联调；<br/>负责人：李明；<br/>截止：{referenceDate}</> : <>请李明在 {referenceDate} 前完成接口联调。</>}</div><p className="field-hint">{isMock ? '每行一条，使用中文标点。' : '负责人、日期或任务指向不清楚时，请在审核中补充核对。模型建议可能有误。'}</p>{health.data && !health.data.configured ? <p className="inline-error">模型尚未配置，导入后会保留原文并提示重试。</p> : null}</div><div className="checklist"><h3>导入之后会发生什么？</h3>{['原文独立归档，保留记录日期', '建议附上原始引句与字段差异', '逐条确认后，再更新任务'].map((text, i) => <p key={text}><span>{i + 1}</span>{text}</p>)}</div></aside>
    </div>
    <section className="panel recent-documents"><div className="section-heading"><h2>项目文档</h2><span className="subtle">刷新后，也能继续审核</span></div>{documents.isPending ? <Loading/> : documents.isError ? <ErrorPanel error={documents.error} retry={() => { void documents.refetch(); }}/> : documents.data.length ? <><div className="document-list">{documents.data.map(doc => <Link className="document-row" key={doc.id} to={'/projects/' + project!.id + '/review/' + doc.id}><span className="document-icon"><FileText size={19}/></span><div><strong>{doc.title}</strong><span>记录于 {doc.reference_date} · 导入于 {time(doc.created_at, project!.timezone)}</span></div><span className="document-count">{doc.proposal_count} 条建议</span><DocumentBadge document={doc}/><ArrowRight size={17}/></Link>)}</div><Pagination page={page} count={documents.data.length} onChange={setPage}/></> : <Empty title="还没有导入记录" description="第一份文档会显示在这里，随时可回看原文与审核进展。"/>}</section>
  </>;
}
export function ImportPage() { const { project } = useWorkspace(); return <ImportForm key={project!.id}/>; }
