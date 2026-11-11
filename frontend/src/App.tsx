import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, Route, Routes, useMatch, useNavigate, useOutletContext } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowUpRight, CheckCircle2, FolderKanban, History, Inbox, Menu, PanelLeftClose, ShieldCheck, Upload, X } from 'lucide-react';
import { api } from './api';
import { Badge, Empty, ErrorPanel, Loading } from './components';
import type { Project } from './types';
import { ProjectsPage } from './pages/ProjectsPage';
import { ImportPage } from './pages/ImportPage';
import { ReviewPage } from './pages/ReviewPage';
import { TasksPage } from './pages/TasksPage';
export interface Workspace { projects: Project[]; project?: Project; notify: (message: string) => void; refresh: () => Promise<void> }
export const useWorkspace = () => useOutletContext<Workspace>();
function Shell() {
  const query = useQuery({ queryKey: ['projects'], queryFn: api.projects });
  const health = useQuery({ queryKey: ['health'], queryFn: api.health, refetchInterval: 15000 });
  const cache = useQueryClient();
  const navigate = useNavigate();
  const match = useMatch('/projects/:projectId/*');
  const projectId = match?.params.projectId;
  const [lastProject, setLastProject] = useState(() => { try { return localStorage.getItem('traceflow.selected-project.v1') || ''; } catch { return ''; } });
  const projects = query.data || [];
  const project = projects.find(p => p.id === projectId);
  const selected = project || projects.find(p => p.id === lastProject) || projects[0];
  const [toast, setToast] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => { if (projectId) { setLastProject(projectId); try { localStorage.setItem('traceflow.selected-project.v1', projectId); } catch { /* storage is optional */ } } }, [projectId]);
  useEffect(() => { if (!toast) return; const timer = setTimeout(() => setToast(''), 6500); return () => clearTimeout(timer); }, [toast]);
  const base = selected ? '/projects/' + selected.id : '';
  const refresh = async () => { await Promise.all([cache.invalidateQueries({ queryKey: ['projects'] }), cache.invalidateQueries({ queryKey: ['documents'] }), cache.invalidateQueries({ queryKey: ['tasks'] })]); };
  const nav = [{ to: '/', label: '项目空间', icon: FolderKanban, end: true }, { to: base ? base + '/import' : '/', label: '文档导入', icon: Upload, end: false }, { to: base ? base + '/review' : '/', label: '建议审核', icon: Inbox, end: false }, { to: base ? base + '/tasks' : '/', label: '任务历史', icon: History, end: false }];
  return <div className="app">
    {menuOpen ? <button className="sidebar-scrim" aria-label="关闭导航遮罩" onClick={() => setMenuOpen(false)}/> : null}
    <aside id="main-sidebar" className={'sidebar ' + (menuOpen ? 'open' : '')}>
      <Link to="/" className="brand" onClick={() => setMenuOpen(false)}><span className="brand-mark">tf<span>↗</span></span><span>TraceFlow<small>让变更有据可循</small></span></Link>
      <div className="workspace-select"><label htmlFor="project-switch">当前项目</label><select id="project-switch" value={selected?.id || ''} disabled={!projects.length} onChange={event => { navigate('/projects/' + event.target.value + '/import'); setMenuOpen(false); }}><option value="" disabled>创建你的第一个项目</option>{projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}</select></div>
      <p className="nav-caption">工作空间</p>
      <nav aria-label="主导航">{nav.map(({ to, label, icon: Icon, end }, index) => <NavLink key={label} end={end} to={to} className={({ isActive }) => 'nav-item ' + (isActive && (index === 0 || !!base) ? 'active' : '')} onClick={() => setMenuOpen(false)}><Icon size={18}/>{label}<span className="nav-number">0{index + 1}</span></NavLink>)}</nav>
      <div className="sidebar-note"><ShieldCheck size={21}/><strong>每一次确认，都留有来处。</strong><p>原始记录、审核决定和任务历史，始终关联在一起。</p><span>记录 → 核对 → 确认 → 追溯</span></div>
      <div className="sidebar-footer"><span className="tiny-dot"/>个人工作空间<Badge tone="green">本机</Badge></div>
    </aside>
    <div className="main-shell"><div className="topbar"><div className="breadcrumb"><button className="icon-button mobile-menu" aria-label={menuOpen ? '收起导航' : '展开导航'} aria-expanded={menuOpen} aria-controls="main-sidebar" onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? <PanelLeftClose size={20}/> : <Menu size={20}/>}</button><Link to="/">工作空间</Link><span>/</span><strong>{project?.name || '项目总览'}</strong></div><div className="topbar-right"><span className={'connection ' + (health.isError ? 'offline' : '')}><span/>{health.isError ? '服务未连接' : health.isPending ? '连接中' : '服务已连接'}</span><span title={health.data?.model}><Badge tone={health.data?.configured ? 'green' : 'amber'}>{health.data?.extractor === 'mock' ? '离线示例' : health.data?.configured ? health.data.provider === 'deepseek' ? 'DeepSeek' : '真实模型' : '模型未配置'}</Badge></span><span className="user-avatar" title="个人工作空间">T</span></div></div>
      <main id="main-content">{query.isPending ? <Loading text="正在打开工作空间…"/> : query.isError ? <ErrorPanel error={query.error} retry={() => { void query.refetch(); }}/> : projectId && !project ? query.isFetching ? <Loading/> : <Empty title="没有找到这个项目" description="项目可能已被移除，请回到项目空间重新选择。" action={<Link className="button primary" to="/">返回项目空间</Link>}/> : <Outlet context={{ projects, project, notify: setToast, refresh } satisfies Workspace}/>}
      </main><footer className="page-footer"><span>TraceFlow · 让项目进展，清晰可溯。</span><span><CheckCircle2 size={13}/>变更由你确认</span></footer>
    </div>
    {toast ? <div className="toast" role="status"><CheckCircle2 size={19}/><span>{toast}</span><button className="icon-button" aria-label="关闭通知" onClick={() => setToast('')}><X size={16}/></button></div> : null}
  </div>;
}
function NotFound() { return <Empty title="这一页还没有记录" description="回到项目空间，继续你的工作。" action={<Link to="/" className="button primary">返回项目空间<ArrowUpRight size={16}/></Link>}/>; }
export function App() { return <Routes><Route element={<Shell/>}><Route index element={<ProjectsPage/>}/><Route path="/projects/:projectId/import" element={<ImportPage/>}/><Route path="/projects/:projectId/review" element={<ReviewPage/>}/><Route path="/projects/:projectId/review/:documentId" element={<ReviewPage/>}/><Route path="/projects/:projectId/tasks" element={<TasksPage/>}/><Route path="*" element={<NotFound/>}/></Route></Routes>; }
