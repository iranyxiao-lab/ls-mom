import request from '@/utils/request';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const enc = encodeURIComponent;

export type Urgency = 'OVERDUE' | 'DUE_SOON' | 'NORMAL';

export interface TaskView {
  id: number;
  taskId: string;
  kind: 'TASK' | 'EXCEPTION' | 'CC' | 'ESCALATION';
  flowInstanceId: number;
  procInstId: string;
  bizType: string;
  bizId: string;
  nodeKey: string;
  nodeName: string;
  assigneeParty?: number;
  candidates: number[];
  dueAt?: string;
  status: 'TODO' | 'DONE' | 'CANCELED';
  outcome?: string;
  comment?: string;
  completedBy?: number;
  completedAt?: string;
  exceptionReason?: string;
  flowVersion?: number;
  formVersion?: number;
  createdAt: string;
}

export interface WorkItem {
  task: TaskView;
  urgency: Urgency;
  flowCode?: string;
  flowVersion?: number;
  initiatorParty?: number;
  initiatorName?: string;
  bizTitle?: string;
}

export interface WorkFilter {
  bizType?: string;
  urgency?: Urgency | '';
  keyword?: string;
}

export interface TaskContext {
  task: TaskView;
  urgency: Urgency;
  instance: Record<string, any>;
  node: { key: string; name: string; type?: string; mode?: string; operation?: string; inputSchema?: any };
  actions: { approve: boolean; reject: boolean; return: boolean; transfer: boolean; resubmit: boolean; urge: boolean };
  returnTargets: { key: string; name: string }[];
  form: any | null;
  fieldPolicy: Record<string, string[]> | null;
  record: Record<string, any>;
  snapshot: Record<string, any>;
  timeline: { at: string; type: string; action: string; actorName?: string; payload?: Record<string, any>; fromStatus?: string; toStatus?: string }[];
}

/** 协同工作中心接口（只返回当前登录人自己的数据）。 */
export const workbenchApi = {
  counts: () =>
    data<{ todo: number; overdue: number; ccUnread: number; messagesUnread: number }>(request({ url: '/api/workbench/counts', method: 'get' })),
  todo: (f: WorkFilter = {}) => data<WorkItem[]>(request({ url: '/api/workbench/todo', method: 'get', params: f })),
  done: (f: WorkFilter = {}) => data<WorkItem[]>(request({ url: '/api/workbench/done', method: 'get', params: f })),
  started: (f: WorkFilter = {}) =>
    data<Record<string, any>[]>(request({ url: '/api/workbench/started', method: 'get', params: { bizType: f.bizType } })),
  cc: (f: WorkFilter = {}) => data<WorkItem[]>(request({ url: '/api/workbench/cc', method: 'get', params: { bizType: f.bizType } })),
  delegations: () => data<Record<string, any>[]>(request({ url: '/api/workbench/delegations', method: 'get' })),
  messages: (unread?: boolean) => data<Record<string, any>[]>(request({ url: '/api/workbench/messages', method: 'get', params: { unread } })),
  readMessages: (ids?: number[]) =>
    data<number>(request({ url: '/api/workbench/messages/read', method: 'post', data: { ids }, headers: { repeatSubmit: false } })),
  parties: (keyword?: string) =>
    data<{ id: number; name: string; orgName?: string }[]>(request({ url: '/api/workbench/parties', method: 'get', params: { keyword } })),
  context: (taskId: string) => data<TaskContext>(request({ url: `/api/workbench/tasks/${enc(taskId)}/context`, method: 'get' }))
};

export type WorkbenchApi = typeof workbenchApi;
