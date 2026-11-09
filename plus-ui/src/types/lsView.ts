/**
 * LS 视图契约（与 ls-mom-form/src/main/resources/ls-schema/*.schema.json 一致）。
 * 产品存储只保存这些格式；设计器内部格式经 fc-adapter 转换。
 */
export type ViewType = 'FORM' | 'LIST' | 'DETAIL' | 'PRINT' | 'MOBILE' | 'DASHBOARD';

export type Widget =
  | 'input'
  | 'textarea'
  | 'number'
  | 'money'
  | 'date'
  | 'datetime'
  | 'switch'
  | 'select'
  | 'radio'
  | 'checkbox'
  | 'cascader'
  | 'remoteSelect'
  | 'dict'
  | 'rate'
  | 'userRef'
  | 'deptRef'
  | 'teamRef'
  | 'assetRef'
  | 'workRef'
  | 'materialRef'
  | 'pointRef'
  | 'conceptRef'
  | 'attachment'
  | 'image'
  | 'file'
  | 'sourceRef'
  | 'confirm'
  | 'readonlyText'
  | 'computed'
  | 'expression'
  | 'registered';

export const WIDGETS: readonly Widget[] = [
  'input', 'textarea', 'number', 'money', 'date', 'datetime', 'switch', 'select', 'radio', 'checkbox', 'cascader', 'remoteSelect', 'dict',
  'rate', 'userRef', 'deptRef', 'teamRef', 'assetRef', 'workRef', 'materialRef', 'pointRef', 'conceptRef', 'attachment', 'image', 'file',
  'sourceRef', 'confirm', 'readonlyText', 'computed', 'expression', 'registered'
] as const;

export type NodeType =
  | 'field'
  | 'subTable'
  | 'group'
  | 'row'
  | 'col'
  | 'tabs'
  | 'tab'
  | 'collapse'
  | 'panel'
  | 'divider'
  | 'text'
  | 'description'
  | 'metricCard'
  | 'relatedSummary'
  | 'relatedList';

export interface LayoutNode {
  type: NodeType;
  key?: string;
  title?: string;
  span?: number;
  text?: string;
  metric?: string;
  concept?: string;
  via?: string;
  columns?: string[];
  collapsed?: boolean;
  children?: LayoutNode[];
}

export interface Option {
  value: string | number | boolean;
  label: string;
  children?: Option[];
}

export type RefFilterOp = 'eq' | 'ne' | 'gt' | 'ge' | 'lt' | 'le' | 'in' | 'like' | 'startsWith';

export interface RefFilter {
  field: string;
  op: RefFilterOp;
  /** 字面量，或 $context.orgId / $context.userId / $context.deptId / $model.<字段> */
  value?: unknown;
}

export interface RefSpec {
  concept: string;
  filter?: RefFilter[];
  labelField?: string;
}

export interface FormField {
  key: string;
  label?: string;
  widget: Widget;
  required?: boolean;
  readonly?: boolean;
  hidden?: boolean;
  default?: unknown;
  placeholder?: string;
  help?: string;
  span?: number;
  minLength?: number;
  maxLength?: number;
  min?: number;
  max?: number;
  precision?: number;
  unit?: string;
  pattern?: string;
  patternMessage?: string;
  dictType?: string;
  options?: Option[];
  multiple?: boolean;
  maxCount?: number;
  accept?: string;
  ref?: RefSpec;
  computed?: { expression: string };
  component?: { code: string; version: string; props?: Record<string, unknown> };
}

export interface SubTable {
  key: string;
  label?: string;
  conceptCode: string;
  minRows?: number;
  maxRows?: number;
  fields: FormField[];
}

export type RuleLevel = 'L0' | 'L1' | 'L2';

export interface FormRule {
  code?: string;
  level: RuleLevel;
  when?: string;
  assert: string;
  field?: string;
  message: string;
}

export interface FormAction {
  key: string;
  label: string;
  operation: string;
  style?: 'primary' | 'default' | 'danger' | 'warning' | 'success';
  confirm?: string;
}

export interface NodePolicy {
  readonly?: string[];
  editable?: string[];
  hidden?: string[];
  required?: string[];
}

export interface FormSchema {
  viewType: 'FORM';
  formCode: string;
  version: number;
  conceptCode: string;
  title?: string;
  labelWidth?: number;
  layout: LayoutNode[];
  fields: FormField[];
  subTables?: SubTable[];
  rules?: FormRule[];
  actions?: FormAction[];
  nodePolicies?: Record<string, NodePolicy>;
  migration?: { strategy?: 'PROMPT' | 'KEEP' | 'UPGRADE'; note?: string };
}

export type ListColumn = string | { key: string; width?: number; fixed?: 'left' | 'right' };

export interface ListViewSchema {
  viewType: 'LIST';
  formCode: string;
  version: number;
  conceptCode: string;
  title?: string;
  columns: ListColumn[];
  filters?: string[];
  defaultSort?: { field: string; dir?: 'asc' | 'desc' }[];
  rowActions?: FormAction[];
  batchActions?: FormAction[];
  export?: { enabled?: boolean; maxRows?: number };
}

export interface DetailSchema {
  viewType: 'DETAIL';
  formCode: string;
  version: number;
  conceptCode: string;
  title?: string;
  groups: { title: string; columns?: number; fields: string[] }[];
  subTables?: string[];
  relatedLists?: { title?: string; concept: string; via: string; columns?: string[] }[];
  timeline?: boolean;
  attachments?: boolean;
}

export interface PrintSchema {
  viewType: 'PRINT';
  formCode: string;
  version: number;
  conceptCode: string;
  templateVersion: string;
  paper?: 'A4' | 'A5' | 'LETTER';
  orientation?: 'portrait' | 'landscape';
  header?: { title?: string; subtitle?: string; showLogo?: boolean };
  sections: { title?: string; columns?: number; fields: string[]; subTable?: string }[];
  signatures?: { label: string; role?: string }[];
  footer?: { text?: string; showPageNo?: boolean };
}

export interface MobileSchema {
  viewType: 'MOBILE';
  formCode: string;
  version: number;
  conceptCode: string;
  title?: string;
  fields: string[];
  photo?: { field: string; maxCount?: number };
  offlineDraft?: boolean;
  actions?: FormAction[];
}

export interface DashboardSchema {
  viewType: 'DASHBOARD';
  formCode: string;
  version: number;
  conceptCode?: string;
  title?: string;
  widgets: { type: 'metricCard' | 'trend' | 'bar' | 'pie' | 'table'; title: string; metric?: string; query?: string; dimensions?: string[] }[];
}

export type AnyViewSchema = FormSchema | ListViewSchema | DetailSchema | PrintSchema | MobileSchema | DashboardSchema;

/** 服务端返回的视图版本。 */
export interface ViewVersion<S = AnyViewSchema> {
  code: string;
  version: number;
  concept: string;
  type: ViewType;
  displayName: string;
  status: 'DRAFT' | 'PUBLISHED' | 'RETIRED';
  schema: S;
  publishedAt?: string;
}

export interface ViewIssue {
  path: string;
  code: string;
  message: string;
}
