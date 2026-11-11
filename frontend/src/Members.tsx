import { useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from './api';
import { useWorkspace } from './App';
import { ErrorPanel, Heading, Loading } from './components';
export const roleLabels = { OWNER: '所有者', EDITOR: '编辑者', VIEWER: '只读成员' };
export function MembersPage() {
  const { project, notify } = useWorkspace();
  return <ProjectMembers key={project!.id} projectId={project!.id} owner={project!.role === 'OWNER'} notify={notify}/>;
}
function ProjectMembers({ projectId, owner, notify }: { projectId: string; owner: boolean; notify: (message: string) => void }) {
  const members = useQuery({ queryKey: ['members', projectId], queryFn: () => api.members(projectId) });
  const [username, setUsername] = useState('');
  const [role, setRole] = useState<'EDITOR' | 'VIEWER'>('VIEWER');
  const change = useMutation({ mutationFn: ({ username, role }: { username: string; role?: 'EDITOR' | 'VIEWER' }) => role ? api.setMember(projectId, username, role) : api.removeMember(projectId, username), onSuccess: async () => { setUsername(''); await members.refetch(); notify('项目成员权限已更新。'); } });
  const submit = (event: FormEvent) => { event.preventDefault(); if (!change.isPending) change.mutate({ username, role }); };
  return <><Heading eyebrow="PROJECT ACCESS" title="项目成员" description="所有者管理成员；编辑者导入、审核与更新；只读成员查看原文、建议和历史。"/>
    <section className="panel members-panel">{members.isPending ? <Loading/> : members.isError ? <ErrorPanel error={members.error}/> : <ul className="member-list">{members.data.map(member => <li key={member.id}><strong>{member.username}</strong><span>{roleLabels[member.role]}</span>{owner && member.role !== 'OWNER' ? <div><select aria-label={'权限：' + member.username} value={member.role} disabled={change.isPending} onChange={e => change.mutate({ username: member.username, role: e.target.value as 'EDITOR' | 'VIEWER' })}><option value="VIEWER">只读成员</option><option value="EDITOR">编辑者</option></select><button className="button small danger" disabled={change.isPending} onClick={() => change.mutate({ username: member.username })}>移除 {member.username}</button></div> : null}</li>)}</ul>}
    {owner ? <form onSubmit={submit}><h2>添加已注册成员</h2><label htmlFor="member-username">成员用户名</label><input id="member-username" required pattern="[a-zA-Z0-9][a-zA-Z0-9_-]{2,39}" maxLength={40} value={username} disabled={change.isPending} onChange={e => setUsername(e.target.value)}/><label htmlFor="member-role">项目角色</label><select id="member-role" value={role} disabled={change.isPending} onChange={e => setRole(e.target.value as 'EDITOR' | 'VIEWER')}><option value="VIEWER">只读成员</option><option value="EDITOR">编辑者</option></select><button className="button primary" disabled={change.isPending || !username}>添加或更新成员</button></form> : null}{change.isError ? <ErrorPanel error={change.error}/> : null}</section></>;
}
