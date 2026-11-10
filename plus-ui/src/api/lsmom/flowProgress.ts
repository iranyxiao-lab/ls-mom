import type { TaskContext } from './workbench';

/** 单据流程进度（GET /api/workbench/biz/{concept}/{id}/progress） */
export interface FlowPerson {
  party: number | null;
  name: string | null;
  role: string | null;
}
export interface FlowCurrent {
  taskId: string;
  nodeKey: string;
  nodeName: string;
  handlers: FlowPerson[];
  dueAt: string | null;
  remainingMinutes: number | null;
  urgency: 'OVERDUE' | 'DUE_SOON' | 'NORMAL';
  mine: boolean;
}
export interface FlowStep {
  key: string;
  name: string;
  type: string;
  state: 'done' | 'current' | 'returned' | 'rejected' | 'pending';
  records: { by: FlowPerson; at: string | null; outcome: string; comment?: string | null }[];
}
export interface FlowProgress {
  flow: {
    instanceId: number;
    flowName: string;
    status: string;
    initiator: FlowPerson;
    startedAt: string;
    endedAt: string | null;
  } | null;
  steps: FlowStep[];
  current: FlowCurrent[];
  myTask: string | null;
  canUrge: boolean;
  returned: { nodeKey: string; nodeName: string; by: FlowPerson; at: string | null; comment: string | null } | null;
}

export interface FlowProgressApi {
  progress: (concept: string, id: number | string) => Promise<FlowProgress>;
  context: (taskId: string) => Promise<TaskContext>;
  complete: (
    taskId: string,
    body: { action: 'APPROVE' | 'REJECT' | 'RESUBMIT'; comment?: string; input?: Record<string, unknown> | null }
  ) => Promise<unknown>;
  returnTask: (taskId: string, target: string, reason?: string) => Promise<unknown>;
  urge: (taskId: string) => Promise<unknown>;
  transfer: (taskId: string, toParty: number, comment?: string) => Promise<unknown>;
  parties: (keyword?: string) => Promise<{ id: number; name: string; orgName?: string }[]>;
}

/** 按需加载真实接口，组件本身不静态依赖 request */
const load = () => import('./flowProgressHttp').then((m) => m.flowProgressHttp);
export const lazyFlowProgressApi: FlowProgressApi = new Proxy({} as FlowProgressApi, {
  get:
    (_t, key: string) =>
    async (...args: unknown[]) =>
      ((await load()) as any)[key](...args)
});
