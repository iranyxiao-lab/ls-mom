/** LS-MOM 通用台账与 Operation 接口类型。 */
export type FieldType = 'STRING' | 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'ENUM' | 'REF' | 'JSON' | 'FILE';

export interface SchemaField {
  code: string;
  label: string;
  type: FieldType;
  required: boolean;
  queryable: boolean;
  sortable: boolean;
  writable: boolean;
  options?: string[];
  optionLabels?: Record<string, string>;
  refConcept?: string;
  dictType?: string;
  maxLength?: number;
  ext?: boolean;
}

export interface SchemaOperation {
  operation: string;
  name: string;
  kind: 'HANDLER' | 'TRANSITION' | 'QUERY';
  risk: string;
  permitted: boolean;
  inputSchema?: Record<string, unknown>;
  /** 迁移类动作允许的起始状态 */
  from?: string[];
  /** 执行后可撤销（注册了补偿 Operation）：直接执行并提供撤销；否则先确认 */
  undoable?: boolean;
}

/** 撤销方式：对同一对象以 input 执行补偿 Operation（版本取执行结果的 version） */
export interface UndoInfo {
  operation: string;
  targetId: string;
  input: Record<string, unknown>;
}

/** 执行前的影响说明 */
export interface OperationImpact {
  operation: string;
  name: string;
  undoable: boolean;
  fromStatus?: string | null;
  toStatus?: string | null;
  /** 执行后进入终态：不能再修改或办理 */
  terminal: boolean;
  lines: string[];
}

export interface ListSchema {
  concept: string;
  displayName: string;
  version: number;
  fields: SchemaField[];
  children: { key: string; concept: string; displayName: string; fields: SchemaField[] }[];
  operations: SchemaOperation[];
  permissions: Record<'list' | 'query' | 'add' | 'edit' | 'remove' | 'export' | 'import', boolean>;
  /** 已发布 LIST 视图（JSON 字符串）：{ columns: string[] } 等 */
  view?: string;
}

export type FilterOp = 'eq' | 'ne' | 'gt' | 'ge' | 'lt' | 'le' | 'like' | 'startsWith' | 'in' | 'notIn' | 'between' | 'isNull' | 'notNull';

export interface QuerySpec {
  filters: { field: string; op: FilterOp; value?: unknown }[];
  sort: { field: string; dir: 'asc' | 'desc' }[];
  page: number;
  size: number;
  expand?: string[];
  groupBy?: string[];
  aggregates?: { fn: 'count' | 'sum' | 'avg' | 'min' | 'max'; field: string; alias: string }[];
}

export interface Page<T = Record<string, unknown>> {
  rows: T[];
  total: number;
  page: number;
  size: number;
}

export interface OperationCall {
  input?: Record<string, unknown>;
  targetId?: string;
  version?: number;
}

export interface ImportReport {
  batchId: number;
  total: number;
  valid: number;
  invalid: number;
  errors: { row: number; field: string; message: string }[];
}

export interface ExportJob {
  id: number;
  concept: string;
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'REJECTED';
  rowCount?: number;
  error?: string;
}
