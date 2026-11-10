import type { BoardWork, Shift } from '@/api/lsmom/productionModel';

/** 本地日期 YYYY-MM-DD */
export const todayStr = (d = new Date()) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** 按时间判断当前班次：夜班 0–8 点、早班 8–16 点、中班 16–24 点 */
export function currentShiftOf(d = new Date()): Shift {
  const h = d.getHours();
  return h < 8 ? 'NIGHT' : h < 16 ? 'DAY' : 'EVENING';
}

const ORDER: Record<string, number> = { NIGHT: 0, DAY: 1, EVENING: 2 };
const OPEN = new Set(['READY', 'ASSIGNED', 'IN_PROGRESS', 'BLOCKED']);

/** 是否今天的任务：排在今天的班次、计划今天开始，或已开工未完成 */
export function isToday(w: BoardWork, today: string) {
  if (!OPEN.has(w.status)) return false;
  if (w.status === 'IN_PROGRESS' || w.status === 'BLOCKED') return true;
  return w.shiftDate === today || String(w.plannedStart ?? '').slice(0, 10) === today;
}

/** 今日任务排序：本班次在前，再按班次先后、紧急程度（1 最急）、计划开始时间 */
export function sortToday(works: BoardWork[], today: string, current: Shift): BoardWork[] {
  return works
    .filter((w) => isToday(w, today))
    .sort(
      (a, b) =>
        Number(a.shift !== current) - Number(b.shift !== current) ||
        (ORDER[a.shift ?? ''] ?? 9) - (ORDER[b.shift ?? ''] ?? 9) ||
        a.priority - b.priority ||
        String(a.plannedStart ?? '').localeCompare(String(b.plannedStart ?? ''))
    );
}
