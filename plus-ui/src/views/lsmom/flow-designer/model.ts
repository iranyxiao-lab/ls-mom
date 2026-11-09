/** 流程配置模型（与后端 FlowDefinition 一致）与树形编辑辅助函数。 */

export type NodeType = 'FILL' | 'AUDIT' | 'CONFIRM' | 'CC' | 'SERVICE' | 'CONDITION' | 'PARALLEL';
export type Mode = 'SINGLE' | 'OR' | 'AND';
export type TimeoutAction = 'REMIND' | 'ESCALATE' | 'REMIND_ESCALATE';

export interface Assignee {
  strategy: string;
  value?: unknown;
  of?: string;
}

export interface FieldPolicy {
  readonly?: string[];
  hidden?: string[];
  editable?: string[];
  required?: string[];
}

export interface Branch {
  name?: string;
  when?: string;
  nodes: FlowNode[];
}

export interface FlowNode {
  key: string;
  type: NodeType;
  name: string;
  assignee?: Assignee;
  mode?: Mode;
  policy?: string;
  fieldPolicy?: FieldPolicy;
  operation?: string;
  permission?: string;
  timeout?: { hours?: number; action?: TimeoutAction };
  branches?: Branch[];
}

export interface FlowDef {
  code: string;
  version?: number;
  name: string;
  concept: string;
  startOn?: string;
  form?: { code: string; version: number } | null;
  callbacks: { approved?: string; rejected?: string; returned?: string; resubmitted?: string; withdrawn?: string };
  orgUnitId?: number | null;
  adminRole?: string;
  nodes: FlowNode[];
}

export const NODE_TYPES: { type: NodeType; label: string; color: string; human: boolean }[] = [
  { type: 'FILL', label: '填报', color: '#2563eb', human: true },
  { type: 'AUDIT', label: '审核', color: '#ea580c', human: true },
  { type: 'CONFIRM', label: '确认', color: '#0d9488', human: true },
  { type: 'CC', label: '抄送', color: '#7c3aed', human: false },
  { type: 'SERVICE', label: '服务', color: '#475569', human: false },
  { type: 'CONDITION', label: '条件分支', color: '#ca8a04', human: false },
  { type: 'PARALLEL', label: '并行分支', color: '#db2777', human: false }
];

export const typeInfo = (t: NodeType) => NODE_TYPES.find((x) => x.type === t)!;
export const isHuman = (t: NodeType) => typeInfo(t).human;

export const STRATEGIES: { value: string; label: string }[] = [
  { value: 'USER', label: '指定人员' },
  { value: 'ROLE', label: '角色' },
  { value: 'DEPT', label: '部门成员' },
  { value: 'TEAM', label: '班组成员' },
  { value: 'FIELD', label: '单据字段' },
  { value: 'INITIATOR', label: '发起人' },
  { value: 'ORG_LEADER', label: '所在组织负责人' },
  { value: 'DEPT_LEADER', label: '部门负责人' },
  { value: 'WORKSHOP_LEADER', label: '车间负责人' },
  { value: 'SUPERIOR_LEADER', label: '上级负责人' },
  { value: 'DATA_SCOPE', label: '数据范围覆盖业务组织的角色' }
];

export const MODES: { value: Mode; label: string }[] = [
  { value: 'SINGLE', label: '单人/抢办' },
  { value: 'OR', label: '或签' },
  { value: 'AND', label: '会签' }
];

export const TIMEOUT_ACTIONS: { value: TimeoutAction; label: string }[] = [
  { value: 'REMIND', label: '提醒办理人' },
  { value: 'ESCALATE', label: '升级给上级负责人' },
  { value: 'REMIND_ESCALATE', label: '提醒并升级' }
];

export const KEY_PATTERN = /^[a-zA-Z][a-zA-Z0-9_]{0,39}$/;
const RESERVED = new Set(['start', 'initiator', 'end_approved', 'end_rejected']);

/** 遍历全部节点（含分支内），回调参数为节点与所在列表。 */
export function walk(nodes: FlowNode[], fn: (n: FlowNode, list: FlowNode[], index: number) => void) {
  nodes.forEach((n, i) => {
    fn(n, nodes, i);
    n.branches?.forEach((b) => walk(b.nodes, fn));
  });
}

export function allKeys(def: FlowDef): Set<string> {
  const s = new Set<string>();
  walk(def.nodes, (n) => s.add(n.key));
  return s;
}

export function findNode(def: FlowDef, key: string): FlowNode | undefined {
  let hit: FlowNode | undefined;
  walk(def.nodes, (n) => {
    if (n.key === key) hit = n;
  });
  return hit;
}

export function nextKey(def: FlowDef, base: string): string {
  const keys = allKeys(def);
  for (let i = 1; ; i++) {
    const k = `${base}${i}`;
    if (!keys.has(k) && !RESERVED.has(k)) return k;
  }
}

export function newNode(def: FlowDef, type: NodeType): FlowNode {
  const info = typeInfo(type);
  const base = { FILL: 'fill', AUDIT: 'audit', CONFIRM: 'confirm', CC: 'cc', SERVICE: 'service', CONDITION: 'cond', PARALLEL: 'par' }[type];
  const n: FlowNode = { key: nextKey(def, base), type, name: info.label };
  if (info.human) {
    n.assignee = { strategy: type === 'CONFIRM' ? 'INITIATOR' : 'DEPT_LEADER', of: type === 'CONFIRM' ? undefined : 'INITIATOR' };
    n.mode = 'SINGLE';
  } else if (type === 'CC') {
    n.assignee = { strategy: 'INITIATOR' };
  } else if (type === 'CONDITION') {
    n.branches = [
      { name: '条件 1', when: '', nodes: [] },
      { name: '其他', nodes: [] }
    ];
  } else if (type === 'PARALLEL') {
    n.branches = [
      { name: '分支 1', nodes: [] },
      { name: '分支 2', nodes: [] }
    ];
  }
  return n;
}

/** 校验节点键：格式、保留字、唯一。返回错误信息或空。 */
export function keyError(def: FlowDef, node: FlowNode, key: string): string {
  if (!KEY_PATTERN.test(key)) return '节点键须以字母开头，只含字母、数字、下划线';
  if (RESERVED.has(key)) return '节点键为保留字';
  let dup = false;
  walk(def.nodes, (n) => {
    if (n !== node && n.key === key) dup = true;
  });
  return dup ? '节点键重复' : '';
}

export function removeNode(def: FlowDef, key: string): boolean {
  let done = false;
  walk(def.nodes, (n, list, i) => {
    if (!done && n.key === key) {
      list.splice(i, 1);
      done = true;
    }
  });
  return done;
}

/** 规范化：去掉空值，发布与差异比较前使用。 */
export function clean<T>(v: T): T {
  if (Array.isArray(v)) return v.map(clean) as T;
  if (v && typeof v === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, x] of Object.entries(v as Record<string, unknown>)) {
      if (x === undefined || x === null || x === '') continue;
      if (k === 'fieldPolicy' && x && typeof x === 'object' && Object.values(x).every((a) => Array.isArray(a) && a.length === 0)) continue;
      if (k === 'timeout' && x && typeof x === 'object' && !(x as any).hours) continue;
      if (Array.isArray(x) && x.length === 0 && k !== 'nodes') continue;
      out[k] = clean(x);
    }
    return out as T;
  }
  return v;
}

export function assigneeText(
  a: Assignee | undefined,
  names: { party: (id: number) => string; role: (k: string) => string; org: (id: number) => string }
) {
  if (!a?.strategy) return '未设置办理人';
  const label = STRATEGIES.find((s) => s.value === a.strategy)?.label ?? a.strategy;
  const v = a.value;
  switch (a.strategy) {
    case 'USER':
      return `${label}：${(Array.isArray(v) ? v : v == null ? [] : [v]).map((x) => names.party(Number(x))).join('、') || '未选择'}`;
    case 'ROLE':
    case 'DATA_SCOPE':
      return `${label}：${v ? names.role(String(v)) : '未选择'}${a.of === 'BIZ_ORG' ? '（业务组织内）' : ''}`;
    case 'DEPT':
    case 'TEAM':
      return `${label}：${v ? names.org(Number(v)) : '业务组织'}`;
    case 'FIELD':
      return `${label}：${v ?? '未选择'}`;
    case 'ORG_LEADER':
    case 'DEPT_LEADER':
    case 'WORKSHOP_LEADER':
    case 'SUPERIOR_LEADER':
      return `${a.of === 'BIZ_ORG' ? '业务组织的' : '发起人的'}${label}`;
    default:
      return label;
  }
}

export interface NodeChange {
  key: string;
  name: string;
  kind: 'ADDED' | 'REMOVED' | 'CHANGED';
  detail?: string;
}

/** 两个版本按节点键比较：新增、删除、属性变化（不含分支内容本身）。 */
export function diffDefs(base: FlowDef, target: FlowDef): NodeChange[] {
  const index = (d: FlowDef) => {
    const m = new Map<string, FlowNode>();
    walk(d.nodes, (n) => m.set(n.key, n));
    return m;
  };
  const a = index(base);
  const b = index(target);
  const strip = (n: FlowNode) => JSON.stringify(clean({ ...n, branches: n.branches?.map((x) => ({ name: x.name, when: x.when })) }));
  const out: NodeChange[] = [];
  for (const [k, n] of b) {
    const o = a.get(k);
    if (!o) out.push({ key: k, name: n.name, kind: 'ADDED', detail: describe(n) });
    else if (strip(o) !== strip(n)) out.push({ key: k, name: n.name, kind: 'CHANGED', detail: changedFields(o, n) });
  }
  for (const [k, n] of a) if (!b.has(k)) out.push({ key: k, name: n.name, kind: 'REMOVED', detail: describe(n) });
  return out;
}

export function describe(n: FlowNode) {
  const t = typeInfo(n.type).label;
  const mode = n.mode && n.mode !== 'SINGLE' ? MODES.find((m) => m.value === n.mode)!.label : '';
  return mode ? `${t} · ${mode}` : t;
}

function changedFields(a: FlowNode, b: FlowNode) {
  const labels: Record<string, string> = {
    name: '名称',
    type: '类型',
    assignee: '办理人',
    mode: '会签方式',
    policy: '表单策略',
    fieldPolicy: '字段策略',
    operation: 'Operation',
    permission: '权限',
    timeout: '超时',
    branches: '分支'
  };
  const ca = clean(a) as any;
  const cb = clean(b) as any;
  return Object.keys(labels)
    .filter((k) => {
      if (k === 'branches') {
        const s = (n: any) => JSON.stringify((n.branches ?? []).map((x: any) => ({ name: x.name, when: x.when })));
        return s(ca) !== s(cb);
      }
      return JSON.stringify(ca[k]) !== JSON.stringify(cb[k]);
    })
    .map((k) => labels[k])
    .join('、');
}
