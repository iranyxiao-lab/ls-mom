import request from '@/utils/request';
import type { FormSchema } from '@/types/lsView';

/** 设计器沙箱试运行（后端 TrialRunner：只回滚事务，正式库不留记录）。 */
export interface TrialSetup {
  flowCode: string;
  flowVersion: number;
  flowName: string;
  concept: string;
  startOn: string;
  nodes: { key: string; name: string; type: string; policyKey: string }[];
  initiators: { party: number; name: string; orgName?: string }[];
}
export interface TrialNodeStep {
  decision?: 'APPROVE' | 'REJECT';
  input?: Record<string, unknown>;
  comment?: string;
  party?: number;
}
export interface TrialStep {
  kind: 'draft' | 'fill' | 'submit' | 'task' | 'returned' | 'end';
  label: string;
  actor?: string;
  ok?: boolean;
  error?: string;
  status?: string;
  node?: string;
  decision?: string;
  editable?: string[];
  required?: string[];
  flowStatus?: string;
  callbacks?: { event: string; operation: string; status: string }[];
}
export interface TrialResult {
  id: number;
  status: 'PASSED' | 'FAILED';
  summary: string;
  trace: TrialStep[];
}

export const trialApi = {
  setup: (formCode: string) => request({ url: '/api/flow/trial/setup', params: { formCode } }).then((r: any) => r.data as TrialSetup),
  run: (body: {
    formCode: string;
    schema?: FormSchema;
    values: Record<string, unknown>;
    initiatorParty?: number;
    nodes: Record<string, TrialNodeStep>;
  }) => request({ url: '/api/flow/trial', method: 'post', data: body, timeout: 60000 }).then((r: any) => r.data as TrialResult),
  list: (formCode: string) =>
    request({ url: '/api/flow/trial', params: { formCode } }).then((r: any) =>
      (r.data as any[]).map((x) => ({ ...x, trace: JSON.parse(x.trace) as TrialStep[] }))
    ),
  clear: (formCode: string) =>
    request({ url: '/api/flow/trial', method: 'delete', params: { formCode } }).then((r: any) => r.data.deleted as number)
};
