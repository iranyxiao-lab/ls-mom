import request from '@/utils/request';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);

export interface AgentKey {
  id: number;
  agentId: number;
  prefix: string;
  status: 'ACTIVE' | 'REVOKED';
  expiresAt: string;
  rotatedTo?: number;
  createdAt: string;
  revokedAt?: string;
  revokeReason?: string;
  lastUsedAt?: string;
}

export interface AgentIdentity {
  id: number;
  code: string;
  name: string;
  partyId: number;
  permissions: string[];
  rateLimitPerMinute: number;
  status: 'ACTIVE' | 'DISABLED';
  description?: string;
  createdAt: string;
  keys: AgentKey[];
  events?: { occurred_at: string; prefix?: string; event: string; actor_party?: number; detail?: string }[];
  approvals?: AgentApproval[];
}

export interface IssuedKey {
  key: string;
  info: AgentKey;
}

export interface AgentApproval {
  id: string;
  agentId: number;
  onBehalfOf: number;
  operation: string;
  targetId?: string;
  targetVersion?: number;
  input: Record<string, any>;
  inputDigest: string;
  reason?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'USED';
  approvedBy?: number;
  approvedAt?: string;
  expiresAt?: string;
  usedAt?: string;
  note?: string;
  createdAt: string;
}

export interface AgentAuditRow {
  id: number;
  occurred_at: string;
  op_code: string;
  actor_type: string;
  actor_party: number;
  agent_name: string;
  on_behalf_of?: number;
  on_behalf_of_name?: string;
  target_concept?: string;
  target_id?: string;
  result: string;
  error_code?: string;
  error_message?: string;
  trace_id?: string;
  batch_id?: string;
  approval_id?: string;
}

export interface AgentApi {
  list(): Promise<AgentIdentity[]>;
  get(id: number): Promise<AgentIdentity>;
  create(body: { code: string; name: string; permissions: string[]; rateLimitPerMinute?: number; description?: string }): Promise<AgentIdentity>;
  updatePermissions(id: number, permissions: string[], rateLimitPerMinute?: number): Promise<AgentIdentity>;
  setEnabled(id: number, on: boolean): Promise<AgentIdentity>;
  issueKey(id: number, expiresInDays?: number): Promise<IssuedKey>;
  rotateKey(keyId: number, graceHours?: number): Promise<IssuedKey>;
  revokeKey(keyId: number, reason?: string): Promise<AgentKey>;
  audit(id: number, batchId?: string): Promise<AgentAuditRow[]>;
}

export interface ApprovalApi {
  pending(): Promise<AgentApproval[]>;
  decide(id: string, approve: boolean, ttlMinutes?: number, note?: string): Promise<AgentApproval>;
}

const noRepeat = { repeatSubmit: false };

export const agentApi: AgentApi = {
  list: () => data(request({ url: '/api/agents', method: 'get' })),
  get: (id) => data(request({ url: `/api/agents/${id}`, method: 'get' })),
  create: (body) => data(request({ url: '/api/agents', method: 'post', data: body })),
  updatePermissions: (id, permissions, rateLimitPerMinute) =>
    data(request({ url: `/api/agents/${id}/permissions`, method: 'put', data: { permissions, rateLimitPerMinute } })),
  setEnabled: (id, on) => data(request({ url: `/api/agents/${id}/${on ? 'enable' : 'disable'}`, method: 'post' })),
  issueKey: (id, expiresInDays) => data(request({ url: `/api/agents/${id}/keys`, method: 'post', data: { expiresInDays }, headers: noRepeat })),
  rotateKey: (keyId, graceHours) =>
    data(request({ url: `/api/agent-keys/${keyId}/rotate`, method: 'post', data: { graceHours }, headers: noRepeat })),
  revokeKey: (keyId, reason) => data(request({ url: `/api/agent-keys/${keyId}/revoke`, method: 'post', data: { reason } })),
  audit: (id, batchId) => data(request({ url: `/api/agents/${id}/audit`, method: 'get', params: { batchId } }))
};

export const approvalApi: ApprovalApi = {
  pending: () => data(request({ url: '/api/agent-approvals', method: 'get' })),
  decide: (id, approve, ttlMinutes, note) =>
    data(request({ url: `/api/agent-approvals/${encodeURIComponent(id)}/decide`, method: 'post', data: { approve, ttlMinutes, note } }))
};
