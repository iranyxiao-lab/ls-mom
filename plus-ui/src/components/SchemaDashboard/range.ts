import type { DashboardRange } from '@/types/lsView';

const pad = (n: number) => String(n).padStart(2, '0');
export const day = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

/** 时间范围 → [from, to)（本地日期，to 不含）。all 返回空。 */
export function rangeOf(r: DashboardRange | undefined, now = new Date()): { from?: string; to?: string } {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const add = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
  switch (r) {
    case 'today':
      return { from: day(today), to: day(add(today, 1)) };
    case 'thisWeek': {
      const monday = add(today, -((today.getDay() + 6) % 7));
      return { from: day(monday), to: day(add(monday, 7)) };
    }
    case 'last7Days':
      return { from: day(add(today, -6)), to: day(add(today, 1)) };
    case 'thisMonth':
      return { from: day(new Date(today.getFullYear(), today.getMonth(), 1)), to: day(new Date(today.getFullYear(), today.getMonth() + 1, 1)) };
    case 'last30Days':
      return { from: day(add(today, -29)), to: day(add(today, 1)) };
    default:
      return {};
  }
}

export const RANGE_TEXT: Record<DashboardRange, string> = {
  today: '今天',
  thisWeek: '本周',
  last7Days: '近 7 天',
  thisMonth: '本月',
  last30Days: '近 30 天',
  all: '全部'
};

/** 指标值显示：比率显示为百分比。 */
export function formatValue(v: unknown, fn?: string, unit?: string): string {
  if (v === null || v === undefined) return '—';
  if (fn === 'ratio') return `${(Number(v) * 100).toFixed(1)}%`;
  const n = Number(v);
  const s = Number.isInteger(n) ? String(n) : n.toFixed(2);
  return unit && unit !== '%' ? `${s} ${unit}` : s;
}
