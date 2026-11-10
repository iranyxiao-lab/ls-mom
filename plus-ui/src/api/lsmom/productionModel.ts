/** 轻生产前端模型：常量、类型与纯函数（不依赖请求层，组件与单元测试可直接引用）。 */
export const REQUEST = 'ls.production.ProductionRequest';
export const SHIFT_PLAN = 'ls.production.ShiftPlan';
export const OUTPUT = 'ls.production.ProductionOutput';
export const MATERIAL = 'ls.production.MaterialRecord';
export const HANDOVER = 'ls.production.ShiftHandover';

export type Shift = 'NIGHT' | 'DAY' | 'EVENING';
export const SHIFTS: { value: Shift; label: string; time: string }[] = [
  { value: 'NIGHT', label: '夜班', time: '00:00–08:00' },
  { value: 'DAY', label: '早班', time: '08:00–16:00' },
  { value: 'EVENING', label: '中班', time: '16:00–24:00' }
];
export const shiftLabel = (s?: string) => SHIFTS.find((x) => x.value === s)?.label ?? s ?? '未排班';

export const WORK_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  READY: '就绪',
  ASSIGNED: '已分派',
  IN_PROGRESS: '进行中',
  BLOCKED: '阻塞',
  PENDING_CONFIRM: '待确认',
  DONE: '已完成',
  CANCELLED: '已取消'
};
export const RISK: Record<string, string> = { OVERDUE: '已逾期', BLOCKED: '阻塞', UNSCHEDULED: '未排班' };

/** 工作板行：工作 + 班次安排 + 生产上下文 + 负责人 + 风险。 */
export interface BoardWork {
  id: number;
  code: string;
  title: string;
  status: string;
  priority: number;
  orgUnitId: number;
  plannedStart?: string;
  plannedEnd?: string;
  progress?: number;
  blockReason?: string;
  version: number;
  sourceId?: number;
  planId?: number;
  planVersion?: number;
  teamId?: number;
  teamName?: string;
  shiftDate?: string;
  shift?: Shift;
  plannedQty?: number;
  unit?: string;
  line?: string;
  risks: string[];
  assignees: { type: string; ref: string; name: string }[];
}

export interface BoardFilter {
  from?: string;
  to?: string;
  teamId?: number;
  status?: string;
  risk?: string;
  requestId?: number;
  mine?: boolean;
}

export interface Team {
  id: number;
  name: string;
  parentName?: string;
  /** 是否在当前用户数据域内（工作板、分派台只列这些；交接班可选全部班组作为接班方）。 */
  inScope?: boolean;
}

export interface WorkDraft {
  title: string;
  quantity?: number;
  unit?: string;
  teamId?: number;
  shiftDate?: string;
  shift?: Shift;
}

/** 按"与 / 和 / 、 / ， / ；"把描述拆成建议条目，数量与单位从"200 件"这样的片段里取。 */
export function suggestWorks(text: string): WorkDraft[] {
  const body = text.replace(/^.*?(前|之前|以前)(完成)?/, '').trim() || text;
  return body
    .split(/[与和、，,；;]|以及/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const m = /(\d+(?:\.\d+)?)\s*([件个台套箱吨kg公斤米支只批]+)/.exec(s);
      return m ? { title: s, quantity: Number(m[1]), unit: m[2] } : { title: s };
    });
}

export interface OpResult {
  targetId: string;
  version: number;
  status: string;
  warnings: string[];
  data?: any;
  /** 可撤销时的撤销方式 */
  undo?: { operation: string; targetId: string; input: Record<string, unknown> } | null;
}
