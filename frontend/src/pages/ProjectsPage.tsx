import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { ArrowDownToLine, ArrowRight, ArrowUpRight, FileText, FolderPlus, Inbox, Layers3, LoaderCircle, Plus } from 'lucide-react';
import { api } from '../api';
import { useWorkspace } from '../App';
import { Badge, Empty, ErrorPanel, Heading, time } from '../components';
export function ProjectsPage() {
  const { projects, refresh, notify } = useWorkspace();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [timezone, setTimezone] = useState('Asia/Hong_Kong');
  const create = useMutation({ mutationFn: () => api.createProject(name.trim(), timezone), onSuccess: async project => { await refresh(); notify('项目已创建，可以导入第一份记录了。'); navigate('/projects/' + project.id + '/import'); } });
  const submit = (event: FormEvent) => { event.preventDefault(); if (name.trim()) create.mutate(); };
  const stats = [{ label: '项目', value: projects.length, icon: Layers3 }, { label: '已归档文档', value: projects.reduce((n, p) => n + p.document_count, 0), icon: FileText }, { label: '待审核建议', value: projects.reduce((n, p) => n + p.pending_count, 0), icon: Inbox }, { label: '已建立任务', value: projects.reduce((n, p) => n + p.task_count, 0), icon: ArrowDownToLine }];
  return <>
    <Heading eyebrow="YOUR WORKSPACE" title="项目空间" description="把零散的项目记录，整理成每一步都有依据的行动。" action={<a className="button primary" href="#new-project"><Plus size={17}/>新建项目</a>}/>
    <div className="stats-strip">{stats.map(stat => <div className="stat" key={stat.label}><span><stat.icon size={17}/>{stat.label}</span><strong>{stat.value.toString().padStart(2, '0')}</strong></div>)}</div>
    <div className="workspace-grid"><section><div className="section-heading"><h2>我的项目 <span>{projects.length}</span></h2><span className="subtle">让每个项目保持独立、清晰</span></div>
      {projects.length ? <div className="project-grid">{projects.map((project, index) => <article className="project-card" key={project.id}><div className="project-card-top"><span className={'project-avatar color-' + index % 3}>{project.name.slice(0, 1)}</span>{project.active_job_count ? <Badge tone="amber">{project.active_job_count} 条后台作业</Badge> : project.pending_count ? <Badge tone="amber">{project.pending_count} 条待审核</Badge> : <Badge tone={project.document_count ? "green" : "neutral"}>{project.document_count ? "暂无待审建议" : "新项目"}</Badge>}</div><Link className="project-title" to={'/projects/' + project.id + '/import'}>{project.name}<ArrowUpRight size={19}/></Link><p className="project-date">{project.timezone} · 创建于 {time(project.created_at, project.timezone)}</p><div className="project-numbers"><span><b>{project.document_count}</b> 份文档</span><span><b>{project.task_count}</b> 个任务</span></div><div className="project-actions"><Link to={'/projects/' + project.id + '/import'}>导入记录<ArrowRight size={14}/></Link><Link to={'/projects/' + project.id + '/review'}>审核建议</Link><Link to={'/projects/' + project.id + '/tasks'}>任务历史</Link></div></article>)}</div> : <div className="panel"><Empty title="从第一个项目开始" description="创建项目后，导入一份会议记录，让任务与原文关联起来。" action={<a href="#project-name" className="button secondary"><FolderPlus size={17}/>创建项目</a>}/></div>}
      <div className="flow-guide"><p className="eyebrow">A CLEARER WAY TO WORK</p><h3>有记录，有核对，也有来处。</h3><div>{['导入记录', '审核建议', '追溯变更'].map((text, index) => <span key={text}><b>0{index + 1}</b>{text}{index < 2 ? <ArrowRight size={14}/> : null}</span>)}</div></div>
    </section><aside id="new-project" className="panel create-panel"><div className="panel-symbol"><FolderPlus size={23}/></div><h2>新建项目</h2><p className="subtle">为一段新的协作，留出独立空间。</p><form onSubmit={submit}><label htmlFor="project-name">项目名称</label><input id="project-name" value={name} onChange={event => setName(event.target.value)} maxLength={120} required placeholder="例如：个人网站重构"/><label htmlFor="project-timezone">项目时区</label><select id="project-timezone" value={timezone} onChange={event => setTimezone(event.target.value)}><option value="Asia/Hong_Kong">香港 / 北京 · UTC+08:00</option><option value="Asia/Singapore">新加坡 · UTC+08:00</option><option value="Australia/Sydney">悉尼 · 随夏令时调整</option><option value="UTC">协调世界时 · UTC</option></select><p className="field-hint">记录日期与历史时间会以该时区显示。</p>{create.isError ? <ErrorPanel error={create.error}/> : null}<button className="button primary full" disabled={!name.trim() || create.isPending}>{create.isPending ? <LoaderCircle size={17} className="spin"/> : <Plus size={17}/>}创建项目</button></form><div className="quiet-note">项目之间的文档、建议和任务独立保存。</div></aside></div>
  </>;
}
