/** DCS 只读集成的常量与类型（不引用请求模块，组件可直接导入运行时值）。 */
export const ALARM_STATUS: Record<string, string> = {
  NEW: '新报警',
  DRAFTED: '已生成草稿',
  NOTIFIED: '已提醒',
  IGNORED: '已忽略',
  SUGGESTED: '建议关联',
  LINKED: '已关联问题'
};
export const ALARM_STATUS_TYPE: Record<string, 'success' | 'info' | 'warning' | 'danger' | 'primary'> = {
  NEW: 'danger',
  DRAFTED: 'warning',
  NOTIFIED: 'primary',
  IGNORED: 'info',
  SUGGESTED: 'warning',
  LINKED: 'success'
};
/** 待处置：仍需有人看一眼的报警。 */
export const OPEN_STATUSES = ['NEW', 'DRAFTED', 'NOTIFIED', 'SUGGESTED'];
export const POLICY: Record<string, string> = { AUTO_DRAFT: '自动生成问题草稿', NOTIFY: '仅提醒', IGNORE: '忽略' };
export const SEVERITY: Record<string, string> = { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '严重' };

export interface DcsPoint {
  id?: number;
  code: string;
  name: string;
  unit?: string;
  range_low?: number | null;
  range_high?: number | null;
  source_system: string;
  asset_id?: number | null;
  org_unit_id?: number | null;
  org_name?: string;
  process_name?: string;
  alarm_policy: string;
  owner_party?: number | null;
  enabled: boolean;
  last_bucket?: string;
  last_avg?: number | null;
  last_value?: number | null;
  last_max?: number | null;
  last_bad?: number;
  last_skew?: number;
  open_alarms?: number;
}

export interface Snapshot {
  bucket_start: string;
  bucket_seconds: number;
  avg_value: number | null;
  min_value: number | null;
  max_value: number | null;
  last_value: number | null;
  sample_count: number;
  bad_count: number;
  skew_count: number;
}

export interface DcsAlarm {
  id: number;
  dedup_key: string;
  source_system?: string;
  point_code: string;
  point_name?: string;
  unit?: string;
  alarm_type: string;
  severity?: string;
  message?: string;
  first_occurred_at: string;
  last_occurred_at: string;
  last_received_at?: string;
  occurrence_count: number;
  last_value?: number | null;
  clock_anomaly: boolean;
  status: string;
  asset_id?: number | null;
  org_unit_id?: number | null;
  org_name?: string;
  issue_id?: number | null;
  issue_code?: string;
  issue_title?: string;
  issue_status?: string;
  issue_version?: number;
  suggested_issue_id?: number | null;
  suggested_issue_code?: string;
  suggested_issue_title?: string;
  trace_id?: string;
  events?: AlarmEvent[];
}

export interface AlarmEvent {
  source_system: string;
  source_event_id: string;
  occurred_at: string;
  received_at: string;
  quality: string;
  clock_anomaly: boolean;
  value?: number | null;
  trace_id?: string;
}

export interface AlarmContext {
  alarm: DcsAlarm;
  window: { from: string; to: string; bucketSeconds: number };
  snapshots: Snapshot[];
  events: AlarmEvent[];
  rawRef: { sourceSystem: string; pointCode: string; from: string; to: string; note: string };
}

/** 页面展示的数值：保留 3 位有效小数。 */
export function num(v: number | null | undefined): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—';
  return String(Math.round(v * 1000) / 1000);
}
