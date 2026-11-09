/** 设备 Pack 的常量与类型（不引用请求模块，组件可直接导入运行时值）。 */
export const ASSET = 'ls.asset.Asset';
export const STATUS_LOG = 'ls.asset.AssetStatusLog';
export const PLAN = 'ls.asset.InspectionPlan';
export const RECORD = 'ls.asset.InspectionRecord';
export const DEFECT = 'ls.asset.Defect';

export const ASSET_STATUS: Record<string, string> = {
  DRAFT: '未投用',
  RUNNING: '运行',
  STANDBY: '备用',
  MAINTENANCE: '停机检修',
  STOPPED: '停机',
  SCRAPPED: '报废'
};
export const ASSET_STATUS_TYPE: Record<string, 'success' | 'info' | 'warning' | 'danger' | 'primary'> = {
  DRAFT: 'info',
  RUNNING: 'success',
  STANDBY: 'primary',
  MAINTENANCE: 'warning',
  STOPPED: 'danger',
  SCRAPPED: 'info'
};
export const ASSET_TYPES = [
  { value: 'PUMP', label: '泵' },
  { value: 'MOTOR', label: '电机' },
  { value: 'VALVE', label: '阀门' },
  { value: 'VESSEL', label: '容器' },
  { value: 'INSTRUMENT', label: '仪表' },
  { value: 'STATION', label: '工位' },
  { value: 'OTHER', label: '其他' }
];

/** 设备状态操作：from 为可操作的当前状态；needReason 时必须填原因。 */
export const ASSET_OPS = [
  { op: 'commission', label: '投用', from: ['DRAFT'], needReason: false },
  { op: 'run', label: '恢复运行', from: ['STANDBY', 'MAINTENANCE', 'STOPPED'], needReason: false },
  { op: 'standby', label: '转备用', from: ['RUNNING'], needReason: false },
  { op: 'maintain', label: '停机检修', from: ['RUNNING', 'STANDBY', 'STOPPED'], needReason: true },
  { op: 'stop', label: '停机', from: ['RUNNING', 'STANDBY'], needReason: true },
  { op: 'scrap', label: '报废', from: ['DRAFT', 'RUNNING', 'STANDBY', 'MAINTENANCE', 'STOPPED'], needReason: true }
];

export const CYCLES: Record<string, string> = { DAILY: '每日', WEEKLY: '每周', MONTHLY: '每月' };
export const PLAN_STATUS: Record<string, string> = { DRAFT: '草稿', ACTIVE: '生效', PAUSED: '暂停' };

export const DEFECT_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  REPORTED: '待处理',
  REPAIRING: '维修中',
  PENDING_VERIFY: '待验证',
  CLOSED: '已关闭',
  CANCELLED: '已取消'
};
export const DEFECT_STATUS_TYPE: Record<string, 'success' | 'info' | 'warning' | 'danger' | 'primary'> = {
  DRAFT: 'info',
  REPORTED: 'danger',
  REPAIRING: 'warning',
  PENDING_VERIFY: 'primary',
  CLOSED: 'success',
  CANCELLED: 'info'
};
export const SEVERITY: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' };

export const TIMELINE_KIND: Record<string, { label: string; type: 'success' | 'info' | 'warning' | 'danger' | 'primary' }> = {
  STATUS: { label: '状态', type: 'info' },
  INSPECTION: { label: '点检', type: 'success' },
  OBSERVATION: { label: '观察', type: 'warning' },
  ALARM: { label: '报警', type: 'danger' },
  DEFECT: { label: '缺陷', type: 'danger' },
  REPAIR: { label: '维修', type: 'warning' },
  VERIFY: { label: '验证', type: 'primary' },
  WORK: { label: '工作', type: 'info' }
};

export interface TimelineItem {
  kind: string;
  at: string;
  title: string;
  detail?: string;
  status?: string;
  concept: string;
  id: number;
}

export interface MyInspection {
  id: number;
  version: number;
  status: string;
  due_date: string;
  result?: string;
  abnormal_count?: number;
  defect_count?: number;
  asset_id: number;
  asset_code: string;
  asset_name: string;
  location?: string;
}

export interface OrgOption {
  id: number;
  name: string;
  unit_type: string;
  parent_name?: string;
}

/** 引用字段在列表里可能是 {id,label} 也可能是编号。 */
export const refId = (v: any): number | undefined => (v == null ? undefined : typeof v === 'object' ? Number(v.id) : Number(v));
export const refLabel = (v: any): string => (v == null ? '' : typeof v === 'object' ? (v.label ?? String(v.id)) : String(v));

/** 数值读数是否超出上下限。 */
export function outOfRange(value: number | null | undefined, lower?: number | null, upper?: number | null): boolean {
  if (value == null || Number.isNaN(Number(value))) return false;
  const v = Number(value);
  return (lower != null && v < Number(lower)) || (upper != null && v > Number(upper));
}
