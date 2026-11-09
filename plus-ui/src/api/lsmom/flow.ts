import request from '@/utils/request';
import type { FlowDef } from '@/views/lsmom/flow-designer/model';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const enc = encodeURIComponent;

export interface FlowIssue {
  path: string;
  code: string;
  message: string;
}

export interface FlowVersion {
  code: string;
  version: number;
  concept: string;
  name: string;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | string;
  definition: FlowDef;
  publishedAt?: string;
}

export interface FlowSummary {
  code: string;
  version: number;
  concept: string;
  name: string;
  status: string;
  publishedAt?: string;
}

/** 流程配置接口：只提交 JSON 配置，不接受 BPMN XML。 */
export const flowApi = {
  list: () => data<FlowSummary[]>(request({ url: '/api/flow/definitions', method: 'get' })),
  history: (code: string) => data<FlowSummary[]>(request({ url: `/api/flow/definitions/${enc(code)}/history`, method: 'get' })),
  get: (code: string, version: number) => data<FlowVersion>(request({ url: `/api/flow/definitions/${enc(code)}/${version}`, method: 'get' })),
  // 校验与预览只读，可连续调用（发布前会再校验一次），不走防重复提交
  validate: (def: FlowDef) =>
    data<FlowIssue[]>(request({ url: '/api/flow/definitions/validate', method: 'post', data: def, headers: { repeatSubmit: false } })),
  preview: (def: FlowDef) =>
    data<{ issues: FlowIssue[]; bpmnXml: string }>(
      request({ url: '/api/flow/definitions/preview', method: 'post', data: def, headers: { repeatSubmit: false } })
    ),
  saveDraft: (def: FlowDef) => data<FlowVersion>(request({ url: '/api/flow/definitions/drafts', method: 'post', data: def })),
  publish: (def: FlowDef) => data<FlowVersion>(request({ url: '/api/flow/definitions/publish', method: 'post', data: def })),
  parties: (keyword?: string) =>
    data<{ id: number; code: string; name: string; orgUnitId?: number; orgName?: string }[]>(
      request({ url: '/api/flow/parties', method: 'get', params: { keyword } })
    ),
  roles: () => data<{ roleKey: string; roleName: string }[]>(request({ url: '/api/flow/roles', method: 'get' })),
  orgs: () =>
    data<{ id: number; code: string; name: string; unitType: string; parentId?: number }[]>(request({ url: '/api/flow/orgs', method: 'get' })),
  conceptFields: (concept: string) =>
    data<{ code: string; label: string; type: string; refConcept?: string }[]>(
      request({ url: `/api/flow/concepts/${enc(concept)}/fields`, method: 'get' })
    ),
  complete: (taskId: string, body: { action: 'APPROVE' | 'REJECT' | 'RESUBMIT'; comment?: string; input?: Record<string, unknown> | null }) =>
    data<{ taskId: string; outcome: string }>(request({ url: `/api/flow/tasks/${enc(taskId)}/complete`, method: 'post', data: body })),
  returnTask: (taskId: string, target: string, reason?: string) =>
    data<{ taskId: string; target: string }>(request({ url: `/api/flow/tasks/${enc(taskId)}/return`, method: 'post', data: { target, reason } })),
  transfer: (taskId: string, toParty: number, comment?: string) =>
    data<{ taskId: string; assignee: number }>(
      request({ url: `/api/flow/tasks/${enc(taskId)}/transfer`, method: 'post', data: { toParty, comment } })
    ),
  urge: (taskId: string) => data<{ taskId: string; urgedAt: string }>(request({ url: `/api/flow/tasks/${enc(taskId)}/urge`, method: 'post' })),
  withdraw: (instanceId: number, reason?: string) =>
    data<unknown>(request({ url: `/api/flow/instances/${instanceId}/withdraw`, method: 'post', data: { reason } })),
  readCc: (bizTaskId: number) => data<void>(request({ url: `/api/flow/cc/${bizTaskId}/read`, method: 'post' })),
  me: () => data<{ partyId: number; orgUnitId?: number }>(request({ url: '/api/flow/me', method: 'get' }))
};
