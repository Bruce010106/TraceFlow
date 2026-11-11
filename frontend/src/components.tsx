import type { ReactNode } from 'react';
import { AlertCircle, ArrowRight, FileText, LoaderCircle, RefreshCw, Check } from 'lucide-react';
import type { TaskStatus } from './types';
export const statusLabels: Record<TaskStatus, string> = { OPEN: '待开始', IN_PROGRESS: '进行中', DONE: '已完成', CANCELLED: '已取消' };
export function Badge({ children, tone = 'neutral' }: { children: ReactNode; tone?: 'green' | 'amber' | 'neutral' | 'red' }) { return <span className={'badge ' + tone}>{children}</span>; }
export function Heading({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) { return <header className="page-heading"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1><p className="subtle">{description}</p></div>{action}</header>; }
export function Empty({ title, description, action }: { title: string; description: string; action?: ReactNode }) { return <div className="empty"><div className="empty-icon"><FileText size={24}/></div><h3>{title}</h3><p>{description}</p>{action}</div>; }
export function Loading({ text = '正在加载…' }: { text?: string }) { return <div className="loading" role="status"><LoaderCircle className="spin" size={20}/>{text}</div>; }
export function ErrorPanel({ error, retry }: { error: unknown; retry?: () => void }) { return <div className="error-panel" role="alert"><AlertCircle size={20}/><div><strong>暂时无法完成操作</strong><p>{error instanceof Error ? error.message : '请稍后再试。'}</p>{retry ? <button className="button small secondary" onClick={retry}><RefreshCw size={14}/>重试</button> : null}</div></div>; }
export function Success({ children }: { children: ReactNode }) { return <div className="success-panel" role="status"><Check size={18}/>{children}</div>; }
export function Arrow() { return <ArrowRight size={16}/>; }
export function Pagination({ page, count, onChange }: { page: number; count: number; onChange: (value: number) => void }) { return <div className="pagination"><span>第 {page + 1} 页 · 本页 {count} 条</span><div><button className="button small secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>上一页</button><button className="button small secondary" disabled={count < 20} onClick={() => onChange(page + 1)}>下一页</button></div></div>; }
export function date(value: string | null | undefined) { return value || '未设置'; }
export function time(value: string, timezone = 'Asia/Hong_Kong') { return new Intl.DateTimeFormat('zh-CN', { timeZone: timezone, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(value)); }
export function today(timezone = 'Asia/Hong_Kong') { const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date()); const get = (type: string) => parts.find(p => p.type === type)?.value; return get('year') + '-' + get('month') + '-' + get('day'); }
