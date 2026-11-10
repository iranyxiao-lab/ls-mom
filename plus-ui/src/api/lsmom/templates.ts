import request from '@/utils/request';
import type { ChangeSet } from './pack';

/** 台账模板库与 LS-IR 片段（见后端 LedgerGenerator）。 */
export interface IrField {
  code: string;
  displayName: string;
  dataType: string;
  required?: boolean;
  refConcept?: string;
  options?: Array<string | { value: string; label?: string }>;
  props?: Record<string, unknown>;
  help?: string;
  readonly?: boolean;
}
export interface IrNode {
  key: string;
  name: string;
  type?: string;
  role?: string;
  editable?: string[];
  required?: string[];
}
export interface IrFragment {
  code?: string;
  name?: string;
  category?: string;
  description?: string;
  entity: string;
  entityName?: string;
  fields: IrField[];
  children?: Array<{ entity: string; displayName?: string; minRows?: number; fields: IrField[] }>;
  flow?: { name?: string; nodes: IrNode[]; initiatorEditable?: string[] };
  rules?: Array<Record<string, unknown>>;
  samples?: Record<string, unknown>;
  provenance?: Record<string, unknown>;
}
export interface TemplateSummary {
  code: string;
  name: string;
  category: string;
  description: string;
  entity: string;
  fields: number;
  children: number;
  nodes: number;
}
export interface DraftBody {
  template?: string;
  fragment?: IrFragment;
  ns: string;
  title: string;
  owner: string;
  requester: string;
  reviewDue?: string;
  files?: Record<string, string>;
  initiatorRoles?: string[];
}
export interface Grant {
  perms: string;
  label: string;
  roles: string[];
}

const data = <T>(p: Promise<any>) => p.then((r) => r.data as T);
export const templateApi = {
  list: () => data<TemplateSummary[]>(request({ url: '/api/pack-templates', method: 'get' })),
  get: (code: string) => data<IrFragment>(request({ url: `/api/pack-templates/${encodeURIComponent(code)}`, method: 'get' })),
  preview: (b: DraftBody) => data<Record<string, string>>(request({ url: '/api/pack-templates/preview', method: 'post', data: b })),
  grantPlan: (b: DraftBody) => data<Grant[]>(request({ url: '/api/pack-templates/grants/plan', method: 'post', data: b })),
  grant: (b: DraftBody) => data<Grant[]>(request({ url: '/api/pack-templates/grants', method: 'post', data: b })),
  draft: (b: DraftBody) => data<ChangeSet>(request({ url: '/api/pack-templates/draft', method: 'post', data: b, timeout: 120000 }))
};
export type TemplateApi = typeof templateApi;
