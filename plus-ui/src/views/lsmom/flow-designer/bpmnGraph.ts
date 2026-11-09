/**
 * 把后端生成的 BPMN XML 转成 LogicFlow 只读图数据，按层次自动布局（从左到右）。
 * 只用于预览：不解释表达式、不回写 XML。
 */

export type GraphKind = 'start' | 'end' | 'terminate' | 'userTask' | 'serviceTask' | 'exclusive' | 'parallel' | 'timer';

export interface GraphNode {
  id: string;
  kind: GraphKind;
  name: string;
  /** 多实例：会签 AND / 或签 OR */
  multi?: 'AND' | 'OR';
  attachedTo?: string;
  layer: number;
  x: number;
  y: number;
}

export interface GraphEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
}

export interface Graph {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

const KIND: Record<string, GraphKind> = {
  startEvent: 'start',
  endEvent: 'end',
  userTask: 'userTask',
  serviceTask: 'serviceTask',
  exclusiveGateway: 'exclusive',
  parallelGateway: 'parallel',
  boundaryEvent: 'timer'
};

export const LAYER_GAP = 190;
export const ROW_GAP = 110;

export function parseBpmn(xml: string): Graph {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length) throw new Error('BPMN 解析失败');
  const process = Array.from(doc.getElementsByTagNameNS('*', 'process'))[0];
  if (!process) throw new Error('BPMN 中没有 process');
  const nodes: GraphNode[] = [];
  const edges: GraphEdge[] = [];
  for (const el of Array.from(process.children)) {
    const tag = el.localName;
    if (tag === 'sequenceFlow') {
      edges.push({
        id: el.getAttribute('id')!,
        source: el.getAttribute('sourceRef')!,
        target: el.getAttribute('targetRef')!,
        label: el.getAttribute('name') || undefined
      });
      continue;
    }
    const kind = KIND[tag];
    if (!kind) continue;
    const n: GraphNode = { id: el.getAttribute('id')!, kind, name: el.getAttribute('name') ?? '', layer: 0, x: 0, y: 0 };
    if (kind === 'end' && Array.from(el.children).some((c) => c.localName === 'terminateEventDefinition')) n.kind = 'terminate';
    if (kind === 'timer') n.attachedTo = el.getAttribute('attachedToRef') ?? undefined;
    const mi = Array.from(el.children).find((c) => c.localName === 'multiInstanceLoopCharacteristics');
    if (mi) {
      const cc = Array.from(mi.children).find((c) => c.localName === 'completionCondition')?.textContent ?? '';
      n.multi = cc.includes('nrOfCompletedInstances') ? 'OR' : 'AND';
    }
    nodes.push(n);
  }
  layout(nodes, edges);
  return { nodes, edges };
}

/** 最长路径分层（流程模板无环）；同层按首次出现顺序排列并居中。边界事件放在宿主任务下方。 */
function layout(nodes: GraphNode[], edges: GraphEdge[]) {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const out = new Map<string, string[]>();
  const indeg = new Map<string, number>(nodes.map((n) => [n.id, 0]));
  for (const e of edges) {
    if (!byId.has(e.source) || !byId.has(e.target)) continue;
    out.set(e.source, [...(out.get(e.source) ?? []), e.target]);
    indeg.set(e.target, (indeg.get(e.target) ?? 0) + 1);
  }
  const queue = nodes.filter((n) => n.kind !== 'timer' && indeg.get(n.id) === 0).map((n) => n.id);
  // 边界事件视为宿主的后继
  for (const n of nodes) if (n.kind === 'timer' && n.attachedTo) out.set(n.attachedTo, [...(out.get(n.attachedTo) ?? []), n.id]);
  for (const n of nodes) if (n.kind === 'timer') indeg.set(n.id, (indeg.get(n.id) ?? 0) + 1);
  const seen = new Set<string>();
  while (queue.length) {
    const id = queue.shift()!;
    if (seen.has(id)) continue;
    seen.add(id);
    const n = byId.get(id)!;
    for (const t of out.get(id) ?? []) {
      const m = byId.get(t)!;
      const step = m.kind === 'timer' ? 0 : 1;
      m.layer = Math.max(m.layer, n.layer + step);
      indeg.set(t, indeg.get(t)! - 1);
      if (indeg.get(t)! <= 0) queue.push(t);
    }
  }
  const layers = new Map<number, GraphNode[]>();
  for (const n of nodes) if (n.kind !== 'timer') layers.set(n.layer, [...(layers.get(n.layer) ?? []), n]);
  for (const [layer, list] of layers) {
    list.forEach((n, i) => {
      n.x = 80 + layer * LAYER_GAP;
      n.y = 200 + (i - (list.length - 1) / 2) * ROW_GAP * 1.6;
    });
  }
  // 边界事件贴在宿主下沿，后续节点放到下一行
  for (const n of nodes) {
    if (n.kind !== 'timer' || !n.attachedTo) continue;
    const host = byId.get(n.attachedTo)!;
    n.x = host.x + 40;
    n.y = host.y + 32;
    n.layer = host.layer;
  }
  for (const n of nodes) {
    const timerFed = edges.some((e) => e.target === n.id && byId.get(e.source)?.kind === 'timer');
    const chainFromTimer = (id: string): boolean =>
      edges.some(
        (e) => e.target === id && (byId.get(e.source)?.kind === 'timer' || (byId.get(e.source)?.kind === 'serviceTask' && chainFromTimer(e.source)))
      );
    if (timerFed || (n.kind === 'end' && chainFromTimer(n.id))) {
      const src = edges.find((e) => e.target === n.id)!;
      const s = byId.get(src.source)!;
      n.x = s.x + (s.kind === 'timer' ? 60 : LAYER_GAP * 0.7);
      n.y = s.y + (s.kind === 'timer' ? ROW_GAP : 0);
    }
  }
}

export interface Highlight {
  added: Set<string>;
  changed: Set<string>;
}

/** BPMN 元素属于哪个配置节点：id 等于节点键，或形如 resolve_<key>、gw_<key>、timer_<key>。 */
export function ownerKey(elementId: string, keys: Set<string>): string | undefined {
  if (keys.has(elementId)) return elementId;
  for (const k of keys) if (elementId.endsWith('_' + k)) return k;
  return undefined;
}

const STYLE = {
  added: { stroke: '#16a34a', strokeWidth: 3, fill: '#dcfce7' },
  changed: { stroke: '#d97706', strokeWidth: 3, fill: '#fef3c7' }
};

/** 转为 LogicFlow render 数据。 */
export function toLogicFlow(g: Graph, hl?: Highlight, keys?: Set<string>) {
  const styleOf = (id: string) => {
    if (!hl || !keys) return undefined;
    const k = ownerKey(id, keys);
    if (k && hl.added.has(k)) return 'added' as const;
    if (k && hl.changed.has(k)) return 'changed' as const;
    return undefined;
  };
  const nodes = g.nodes.map((n) => {
    const mark = styleOf(n.id);
    const text = n.multi
      ? `${n.name}\n（${n.multi === 'AND' ? '会签' : '或签'}）`
      : n.kind === 'parallel'
        ? '+'
        : n.kind === 'exclusive'
          ? '×'
          : n.name;
    const base: any = {
      id: n.id,
      x: n.x,
      y: n.y,
      text,
      properties: { kind: n.kind, mark, name: n.name, style: { ...NODE_STYLE[n.kind], ...(mark ? STYLE[mark] : {}) } }
    };
    switch (n.kind) {
      case 'start':
        return { ...base, type: 'circle', properties: { ...base.properties, r: 18 }, text: '开始' };
      case 'end':
      case 'terminate':
        return { ...base, type: 'circle', properties: { ...base.properties, r: 18 }, text: n.name };
      case 'timer':
        return { ...base, type: 'circle', properties: { ...base.properties, r: 12 }, text: '⏱' };
      case 'exclusive':
      case 'parallel':
        return { ...base, type: 'diamond', properties: { ...base.properties, rx: 22, ry: 22 } };
      default:
        return { ...base, type: 'rect', properties: { ...base.properties, width: 130, height: n.multi ? 60 : 50, radius: 8 } };
    }
  });
  const edges = g.edges.map((e) => ({ id: e.id, type: 'polyline', sourceNodeId: e.source, targetNodeId: e.target, text: e.label }));
  return { nodes, edges };
}

export const NODE_STYLE: Record<string, Record<string, unknown>> = {
  start: { stroke: '#16a34a', fill: '#f0fdf4' },
  end: { stroke: '#2563eb', strokeWidth: 3, fill: '#eff6ff' },
  terminate: { stroke: '#dc2626', strokeWidth: 3, fill: '#fef2f2' },
  timer: { stroke: '#9333ea', fill: '#faf5ff' },
  userTask: { stroke: '#ea580c', fill: '#fff7ed' },
  serviceTask: { stroke: '#64748b', fill: '#f8fafc' },
  exclusive: { stroke: '#ca8a04', fill: '#fefce8' },
  parallel: { stroke: '#db2777', fill: '#fdf2f8' }
};
