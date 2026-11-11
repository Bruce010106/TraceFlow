import { useEffect, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { QueryClient } from '@tanstack/react-query';
import { api, ApiError } from './api';
import { ErrorPanel, Loading } from './components';

// Keep the active identity observer attached while dropping all previous account data.
export async function clearWorkspace(cache: QueryClient) {
  await cache.cancelQueries();
  cache.removeQueries({ predicate: query => query.queryKey[0] !== 'me' });
  cache.getMutationCache().clear();
}

export function AuthGate({ children }: { children: ReactNode }) {
  const cache = useQueryClient();
  const me = useQuery({ queryKey: ['me'], queryFn: async () => { try { return await api.me(); } catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error; } }, retry: false, refetchInterval: 60000 });
  useEffect(() => {
    const expire = () => { void clearWorkspace(cache).then(() => { cache.setQueryData(['me'], null); }); };
    window.addEventListener('traceflow:unauthenticated', expire);
    return () => window.removeEventListener('traceflow:unauthenticated', expire);
  }, [cache]);
  if (me.isPending) return <Loading text="正在检查登录状态…"/>;
  if (me.isError) return <div className="auth-page"><ErrorPanel error={me.error} retry={() => { void me.refetch(); }}/></div>;
  return me.data ? children : <Login/>;
}
function Login() {
  const cache = useQueryClient();
  const [register, setRegister] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const login = useMutation({ mutationFn: async () => { if (register) await api.register(username, password); return api.login(username, password); }, onSuccess: async user => { setPassword(''); await clearWorkspace(cache); cache.setQueryData(['me'], user); } });
  const submit = (event: FormEvent) => { event.preventDefault(); if (!login.isPending) login.mutate(); };
  return <main className="auth-page"><section className="panel auth-card"><p className="eyebrow">TRACEFLOW</p><h1>{register ? '创建个人账号' : '登录工作空间'}</h1><p className="subtle">让项目记录、审核决定和任务历史关联在一起。</p><form onSubmit={submit}>
    <label htmlFor="auth-username">用户名</label><input id="auth-username" autoComplete="username" required pattern="[a-zA-Z0-9][a-zA-Z0-9_-]{2,39}" minLength={3} maxLength={40} value={username} onChange={e => setUsername(e.target.value)} disabled={login.isPending}/><p className="field-hint">3–40 位英文字母、数字、下划线或短横线，不区分大小写。</p>
    <label htmlFor="auth-password">密码</label><input id="auth-password" type="password" autoComplete={register ? 'new-password' : 'current-password'} required minLength={12} maxLength={64} value={password} onChange={e => setPassword(e.target.value)} disabled={login.isPending}/><p className="field-hint">12–64 个字符，UTF-8 编码不超过 72 字节。</p>
    {login.isError ? <ErrorPanel error={login.error}/> : null}<button className="button primary full" disabled={login.isPending}>{login.isPending ? '正在处理…' : register ? '注册并登录' : '登录'}</button></form>
    <button className="text-button" disabled={login.isPending} onClick={() => { setRegister(!register); login.reset(); }}>{register ? '已有账号，去登录' : '没有账号，创建账号'}</button><p className="field-hint">登录后仅显示你创建或加入的项目。</p></section></main>;
}
