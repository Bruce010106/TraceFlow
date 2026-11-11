import { CheckCircle2, Clock3, LoaderCircle, RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, time } from './components';
import type { DocumentSummary, ExtractionJob } from './types';

export const isExtractionActive = (status?: string) => ['RUNNING', 'QUEUED', 'RETRY_WAIT'].includes(status || '');
export function DocumentBadge({ document }: { document: DocumentSummary }) {
  if (document.extraction_status === 'FAILED') return <Badge tone="red">抽取未完成</Badge>;
  if (document.extraction_status === 'QUEUED') return <Badge tone="amber">已排队</Badge>;
  if (document.extraction_status === 'RETRY_WAIT') return <Badge tone="amber">等待自动重试</Badge>;
  if (document.extraction_status === 'RUNNING') return <Badge tone="amber">后台抽取中</Badge>;
  return <Badge tone={document.pending_count ? 'amber' : document.proposal_count ? 'green' : 'neutral'}>{document.pending_count ? document.pending_count + ' 条待审核' : document.proposal_count ? '已处理' : '无需审核'}</Badge>;
}
const messages: Record<string, string> = {
  MODEL_NOT_CONFIGURED: '模型尚未配置。请完成本机模型配置并重启后端。',
  MODEL_CONFIG_CHANGED: '模型配置与入队时不同。确认当前配置后，可发起新一轮抽取。',
  MODEL_AUTH_ERROR: '模型服务鉴权失败。请检查本机 API Key 并重启后端。',
  MODEL_RATE_LIMITED: '模型服务暂时限流。',
  MODEL_TIMEOUT: '模型响应超时，原文已保存。',
  MODEL_UNAVAILABLE: '模型服务暂时不可用。',
  MODEL_CONNECTION_ERROR: '无法连接模型服务，请检查网络。',
  MODEL_REQUEST_REJECTED: '模型服务未接受请求，请检查模型名与服务配置。',
  MODEL_REFUSED: '模型未返回任务建议，请检查记录内容。',
  MODEL_INCOMPLETE: '模型输出被截断，本次未生成建议。可以缩短记录后重新导入。',
  MODEL_INVALID_OUTPUT: '模型返回格式不符合要求，本次未生成建议。',
  MODEL_INVALID_EVIDENCE: '模型引句与原文不一致，本次未生成建议。',
  MODEL_INVALID_TARGET: '模型引用了不在当前项目快照中的任务，本次未生成建议。',
  MODEL_DUPLICATE_PROPOSAL: '模型返回重复建议，本次未生成建议。',
  QUEUE_STORAGE_ERROR: '保存建议时遇到数据库错误，任务没有发生变化。',
  LEASE_EXPIRED: '上一次后台处理被中断，原文和任务快照仍保留。',
  WORKER_STOPPED: '后台服务已停止或重启，作业仍保存在数据库中。',
  INVALID_MOCK_INPUT: '离线示例的日期或任务 ID 无效，请修改记录后重新导入。',
  TOO_MANY_PROPOSALS: '建议超过 20 条，请拆分记录后重新导入。',
};
const attemptLabels: Record<string, string> = { RUNNING: '处理中', SUCCEEDED: '成功', FAILED: '失败', EXPIRED: '处理中断', ABANDONED: '服务停止' };
export function ExtractionStatus({ job, onRetry, pending, timezone }: { job?: ExtractionJob; onRetry?: () => void; pending: boolean; timezone: string }) {
  if (!job) return null;
  const failed = job.status === 'FAILED';
  const processing = job.status === 'RUNNING' || pending;
  const queued = job.status === 'QUEUED';
  const waiting = job.status === 'RETRY_WAIT';
  const title = processing ? '后台正在分析原文…' : queued ? '原文已保存，后台作业已排队' : waiting ? '暂未完成，等待自动重试' : failed ? '本轮抽取已结束，尚未生成建议' : '抽取已完成，任务变更以人工审核为准';
  const detail = processing ? '离开页面也会继续处理。任务只在你审核确认后更新。' : queued ? '作业已持久保存，后台领取后开始分析。你可以稍后回来查看。' : waiting ? (messages[job.error_code || ''] || '本次处理暂未完成。') + ' 下次尝试时间：' + (job.next_run_at ? time(job.next_run_at, timezone) : '稍后') + '。无需手动重复提交。' : failed ? (messages[job.error_code || ''] || '本轮抽取未完成，任务没有发生变化。') + (job.attempt_count >= job.max_attempts ? ' 自动重试次数已用完。' : '') : (job.resolved_model || job.model) + (job.duration_ms != null ? ' · 用时 ' + (job.duration_ms / 1000).toFixed(1) + 's' : '') + (job.input_tokens != null ? ' · 输入 ' + job.input_tokens + ' / 输出 ' + (job.output_tokens ?? '—') + ' tokens' : '');
  return <div className={'extraction-notice ' + (failed && !pending ? 'failed' : '')} role={failed ? 'alert' : 'status'}>
    <span className="extraction-icon">{processing ? <LoaderCircle size={20} className="spin"/> : failed ? <TriangleAlert size={20}/> : waiting ? <RefreshCw size={20}/> : queued ? <Clock3 size={20}/> : <CheckCircle2 size={20}/>}</span>
    <div><strong>{title}</strong><p>{detail}</p><p className="attempt-summary">{job.generation > 1 ? '第 ' + job.generation + ' 轮 · ' : ''}{job.attempt_count ? '已尝试 ' + job.attempt_count + ' / ' + job.max_attempts + ' 次' : '尚未开始 · 最多 ' + job.max_attempts + ' 次尝试'}</p>
      {job.attempts?.length ? <details className="attempt-details"><summary>查看后台尝试记录</summary><ul>{job.attempts.map(attempt => <li key={attempt.attempt_no}><strong>第 {attempt.attempt_no} 次 · {attemptLabels[attempt.status] || attempt.status}</strong><span>{time(attempt.started_at, timezone)}{attempt.error_code ? ' · ' + (messages[attempt.error_code] || '本次未完成') : ''}</span></li>)}</ul></details> : null}
    </div>
    {failed && onRetry ? <button className="button small secondary" onClick={onRetry} disabled={pending}>{pending ? <LoaderCircle size={15} className="spin"/> : <RefreshCw size={15}/>}重新抽取</button> : null}
  </div>;
}
