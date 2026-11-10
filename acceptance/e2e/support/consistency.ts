/**
 * 跨模块一致性断言（acceptance-walkthrough 2.4），只读：
 *   业务状态（通用 CRUD 读取）、流程实例与待办读模型（/api/flow/biz）、业务时间轴（/api/timeline）、
 *   Operation 审计与领域事件 / Outbox（命名 Query ls.audit.*）、看板指标（命名 Query 或指标查询），并核对同一 trace_id。
 * 用法：
 *   const before = await metric(viewer, { query: 'ls.ui.homeMyReviews', field: 'total' });
 *   … 执行业务动作，取得响应的 traceId …
 *   const r = await consistency(viewer, concept, id, { status: 'PUBLISHED', flowStatus: 'APPROVED', doneNode: 'approve',
 *     timeline: /审批|发布/, auditOp: 'ls.training.Course.publish', eventType: /published/, traceId, metric: { ...m, before, delta: -1 } });
 *   expect(r.failed).toEqual([]);
 */
import type { Session } from './walk.ts';

export interface MetricRef {
  /** 命名 Query 编码（取第一行的 field，没有行时为 0）或指标编码（取 rows[0].value） */
  query?: string;
  metric?: string;
  params?: Record<string, unknown>;
  field?: string;
  /** 指标查询的身份（默认与 viewer 相同），如"待我审核"要用办理人身份看 */
  as?: Session;
}

export interface Expectation {
  status?: string;
  flowStatus?: string;
  /** 该节点的待办已完成（DONE） */
  doneNode?: string;
  /** 流程结束后不应再有待办（默认：流程不在进行中时检查） */
  noOpenTasks?: boolean;
  timeline?: string | RegExp;
  auditOp?: string;
  eventType?: string | RegExp;
  /** 业务动作响应里的 X-Trace-Id：须同时出现在审计（及时间轴 / 事件）中 */
  traceId?: string | null;
  metric?: MetricRef & { before: number; delta: number };
}

export interface Check {
  name: string;
  ok: boolean;
  detail: string;
}

export interface Snapshot {
  record: any;
  instances: any[];
  tasks: any[];
  timeline: any[];
  audit: any[];
  events: any[];
}

const data = async (s: Session, method: string, url: string, body?: unknown) => {
  const r = await s.api(method, url, body);
  if (r.body?.code !== 200) throw new Error(`${method} ${url} 失败：${r.body?.msg ?? r.status}`);
  return r.body.data;
};
const rows = async (s: Session, query: string, params: Record<string, unknown>) => (await data(s, 'POST', `/api/q/${query}`, params)).rows as any[];

export async function snapshot(viewer: Session, concept: string, id: string | number): Promise<Snapshot> {
  const sid = String(id);
  const [record, flow, timeline, audit, events] = await Promise.all([
    data(viewer, 'GET', `/api/crud/${encodeURIComponent(concept)}/${sid}`),
    data(viewer, 'GET', `/api/flow/biz/${encodeURIComponent(concept)}/${sid}`),
    data(viewer, 'GET', `/api/timeline/${encodeURIComponent(concept)}/${sid}`),
    rows(viewer, 'ls.audit.objectOperations', { concept, id: sid }),
    rows(viewer, 'ls.audit.objectEvents', { concept, id: sid })
  ]);
  // jsonb 列可能以字符串或 { type, value } 返回
  const json = (v: any) => (typeof v === 'string' ? JSON.parse(v) : v && typeof v === 'object' && 'value' in v ? JSON.parse(v.value) : v ?? []);
  for (const e of events) e.outbox = json(e.outbox);
  return { record, instances: (flow.instances ?? []).map((x: any) => x.instance ?? x), tasks: flow.tasks ?? [], timeline, audit, events };
}

export async function metric(viewer: Session, m: MetricRef): Promise<number> {
  const s = m.as ?? viewer;
  if (m.query) {
    const r = await rows(s, m.query, m.params ?? {});
    return r.length ? Number(r[0][m.field ?? 'total'] ?? 0) : 0;
  }
  const r = await data(s, 'POST', `/api/metrics/${m.metric}/query`, m.params ?? {});
  return Number(r.rows?.[0]?.[m.field ?? 'value'] ?? 0);
}

const match = (v: string | undefined | null, p: string | RegExp) => (v == null ? false : typeof p === 'string' ? v.includes(p) : p.test(v));

export async function consistency(viewer: Session, concept: string, id: string | number, exp: Expectation): Promise<{ snap: Snapshot; checks: Check[]; failed: Check[] }> {
  const snap = await snapshot(viewer, concept, id);
  const checks: Check[] = [];
  const add = (name: string, ok: boolean, detail: string) => checks.push({ name, ok, detail });
  const inst = snap.instances[snap.instances.length - 1];

  if (exp.status) add('业务状态', snap.record?.status === exp.status, `期望 ${exp.status}，实际 ${snap.record?.status}`);
  if (exp.flowStatus) add('流程实例', inst?.status === exp.flowStatus, `期望 ${exp.flowStatus}，实际 ${inst?.status ?? '（无实例）'}`);
  if (exp.doneNode) {
    const t = snap.tasks.filter((x) => x.nodeKey === exp.doneNode);
    add('待办已完成', t.length > 0 && t.every((x) => x.status === 'DONE'), `节点 ${exp.doneNode}：${t.map((x) => x.status).join(',') || '（无待办）'}`);
  }
  const open = snap.tasks.filter((x) => x.status === 'TODO');
  if (exp.noOpenTasks ?? (inst && inst.status !== 'RUNNING')) add('待办读模型与流程一致', open.length === 0, `流程 ${inst?.status}，仍有 ${open.length} 条待办`);
  if (inst?.status === 'RUNNING') add('进行中的流程有待办', open.length > 0, `进行中，待办 ${open.length} 条`);
  if (exp.timeline) {
    const hit = snap.timeline.find((e) => match(`${e.type} ${e.action ?? ''} ${e.toStatus ?? ''}`, exp.timeline!));
    add('时间轴', !!hit, hit ? `${hit.type} ${hit.action ?? ''} → ${hit.toStatus ?? ''}` : `没有匹配 ${exp.timeline} 的条目（共 ${snap.timeline.length} 条）`);
  }
  if (exp.auditOp) {
    const a = snap.audit.filter((x) => x.opCode === exp.auditOp);
    add('审计', a.some((x) => x.result === 'SUCCESS'), `${exp.auditOp}：${a.map((x) => x.result).join(',') || '（无记录）'}`);
  }
  if (exp.eventType) {
    const e = snap.events.filter((x) => match(x.type, exp.eventType!));
    add('领域事件', e.length > 0, e.map((x) => x.type).join(',') || `没有匹配 ${exp.eventType} 的事件`);
  }
  const stuck = snap.events.flatMap((e) => (e.outbox ?? []).filter((o: any) => ['FAILED', 'DEAD'].includes(o.status)).map((o: any) => `${e.type}→${o.destination}`));
  add('Outbox 投递', stuck.length === 0, stuck.length ? `投递失败：${stuck.join('、')}` : `${snap.events.length} 个事件无投递失败`);
  if (exp.traceId) {
    const inAudit = snap.audit.some((x) => x.traceId === exp.traceId);
    const inTimeline = snap.timeline.some((e) => e.traceId === exp.traceId);
    const traced = await rows(viewer, 'ls.audit.trace', { traceId: exp.traceId });
    add('trace_id 一致', inAudit && traced.length > 0, `审计 ${inAudit ? '有' : '无'}、时间轴 ${inTimeline ? '有' : '无'}、追踪 ${traced.length} 条（${exp.traceId}）`);
  }
  if (exp.metric) {
    const now = await metric(viewer, exp.metric);
    add('看板指标', now - exp.metric.before === exp.metric.delta, `${exp.metric.query ?? exp.metric.metric}：${exp.metric.before} → ${now}（期望变化 ${exp.metric.delta}）`);
  }
  return { snap, checks, failed: checks.filter((c) => !c.ok) };
}
