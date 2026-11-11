import { CheckCircle2, LoaderCircle, RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge } from './components';
import type { DocumentSummary, ExtractionJob } from './types';

const running = (status?: string) => ['RUNNING', 'QUEUED', 'RETRY_WAIT'].includes(status || '');
export function DocumentBadge({ document }: { document: DocumentSummary }) {
  if (document.extraction_status === 'FAILED') return <Badge tone="red">抽取未完成</Badge>;
  if (running(document.extraction_status)) return <Badge tone="amber">正在抽取</Badge>;
  return <Badge tone={document.pending_count ? 'amber' : document.proposal_count ? 'green' : 'neutral'}>{document.pending_count ? document.pending_count + ' 条待审核' : document.proposal_count ? '已处理' : '无需审核'}</Badge>;
}
const messages: Record<string, string> = {
  MODEL_NOT_CONFIGURED: '模型尚未配置。请完成本机模型配置并重启后端，再重新抽取。',
  MODEL_AUTH_ERROR: '模型服务鉴权失败。请检查本机 API Key 并重启后端。',
  MODEL_RATE_LIMITED: '模型服务暂时限流，请稍后再手动重试。',
  MODEL_TIMEOUT: '模型响应超时，原文已保存。你可以稍后重新抽取。',
  MODEL_UNAVAILABLE: '模型服务暂时不可用，请稍后再试。',
  MODEL_CONNECTION_ERROR: '无法连接模型服务，请检查网络后重新抽取。',
  MODEL_RESPONSE_TOO_LARGE: '模型返回内容过长，本次未生成建议。可以缩短记录后重新导入。',
  MODEL_REQUEST_REJECTED: '模型服务未接受请求，请检查模型名与服务配置。',
  MODEL_REFUSED: '模型未返回任务建议，请检查记录内容后再试。',
  MODEL_INCOMPLETE: '模型输出被截断，本次未生成建议。可以缩短记录后重新导入。',
  MODEL_INVALID_OUTPUT: '模型返回格式不符合要求，本次未生成建议。',
  MODEL_INVALID_EVIDENCE: '模型引句与原文不一致，本次未生成建议。',
  MODEL_INVALID_TARGET: '模型引用了不在当前项目快照中的任务，本次未生成建议。',
  MODEL_DUPLICATE_PROPOSAL: '模型返回重复建议，本次未生成建议。',
  INVALID_MOCK_INPUT: '离线示例的日期或任务 ID 无效，请修改记录后重新导入。',
  TOO_MANY_PROPOSALS: '本次建议超过 20 条，请拆分记录后重新导入。',
};
export function ExtractionStatus({ job, onRetry, pending }: { job?: ExtractionJob; onRetry: () => void; pending: boolean }) {
  if (!job) return null;
  const failed = job.status === 'FAILED';
  const busy = running(job.status) || pending;
  return <div className={'extraction-notice ' + (failed && !pending ? 'failed' : '')} role={failed ? 'alert' : 'status'}>
    <span className="extraction-icon">{busy ? <LoaderCircle size={20} className="spin"/> : failed ? <TriangleAlert size={20}/> : <CheckCircle2 size={20}/>}</span>
    <div><strong>{busy ? '正在分析原文，请稍候…' : failed ? '原文已保存，抽取未完成' : '抽取已完成，任务变更以人工审核为准'}</strong>
      <p>{busy ? '任务不会自动变化。分析完成后，在这里核对建议与来源证据。' : failed ? messages[job.error_code || ''] || '本次抽取未完成，请稍后重新抽取；任务没有发生变化。' : (job.resolved_model || job.model) + (job.duration_ms != null ? ' · 用时 ' + (job.duration_ms / 1000).toFixed(1) + 's' : '') + (job.input_tokens != null ? ' · 输入 ' + job.input_tokens + ' / 输出 ' + (job.output_tokens ?? '—') + ' tokens' : '')}
        {job.attempt_count > 1 ? ' · 第 ' + job.attempt_count + ' 次尝试' : ''}</p>
    </div>
    {failed ? <button className="button small secondary" onClick={onRetry} disabled={pending}>{pending ? <LoaderCircle size={15} className="spin"/> : <RefreshCw size={15}/>}重新抽取</button> : null}
  </div>;
}
