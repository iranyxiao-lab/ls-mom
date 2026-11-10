/** 培训考试：前端模型与纯函数（与后端判分规则一致：客观题按选项集合完全一致计分）。 */
export const QTYPES: Record<string, string> = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', ESSAY: '简答' };

export interface Choice {
  key: string;
  content: string;
}

export interface AnswerItem {
  seq: number;
  qtype: string;
  stem: string;
  choices: string | Choice[] | null;
  score: number;
  response?: string | null;
  earned?: number | null;
  correct?: boolean | null;
  referenceAnswer?: string | null;
}

export interface MyExam {
  id: number;
  title: string;
  status: string;
  start_at: string;
  end_at: string;
  duration_minutes: number;
  total_score: number;
  course_title: string;
  pass_score: number;
  answer_id?: number | null;
  answer_version?: number | null;
  answer_status?: string | null;
  deadline?: string | null;
  score?: number | null;
  passed?: boolean | null;
  essay_pending?: boolean | null;
  auto_submitted?: boolean | null;
  /** 服务器当前时间（与开始 / 结束时间同一时区）。 */
  server_now?: string;
}

export function parseChoices(c: AnswerItem['choices']): Choice[] {
  if (!c) return [];
  if (Array.isArray(c)) return c;
  try {
    const v = JSON.parse(c);
    return Array.isArray(v) ? v : [];
  } catch {
    return [];
  }
}

/** 多选作答：选项键排序后逗号分隔。 */
export const joinKeys = (keys: string[]) => [...new Set(keys)].sort().join(',');
export const splitKeys = (s?: string | null) =>
  s
    ? s
        .split(',')
        .map((x) => x.trim())
        .filter(Boolean)
    : [];

const ts = (s?: string | null) => (s ? new Date(String(s).replace(' ', 'T')).getTime() : NaN);

/** 剩余秒数（不小于 0）。 */
export function remainingSeconds(deadline: string | null | undefined, now = Date.now()): number {
  const d = ts(deadline);
  return Number.isNaN(d) ? 0 : Math.max(0, Math.floor((d - now) / 1000));
}

export function fmtCountdown(sec: number): string {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/** 考试在我这里的状态：未开始 / 可参加 / 作答中 / 待评 / 已出分 / 已结束。 */
export function examPhase(
  e: MyExam,
  now = e.server_now ? ts(e.server_now) : Date.now()
): 'NOT_STARTED' | 'OPEN' | 'IN_PROGRESS' | 'PENDING' | 'DONE' | 'MISSED' {
  if (e.answer_status === 'GRADED') return e.essay_pending ? 'PENDING' : 'DONE';
  if (e.answer_status === 'IN_PROGRESS') return 'IN_PROGRESS';
  if (e.status === 'CLOSED' || now > ts(e.end_at)) return 'MISSED';
  if (now < ts(e.start_at)) return 'NOT_STARTED';
  return 'OPEN';
}

export const PHASE_LABEL: Record<string, string> = {
  NOT_STARTED: '未开始',
  OPEN: '可参加',
  IN_PROGRESS: '作答中',
  PENDING: '简答待评',
  DONE: '已出分',
  MISSED: '已结束'
};

/** 简答评分校验：返回 {题号: 错误}。 */
export function essayErrors(items: AnswerItem[], scores: Record<string, number | null | undefined>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const it of items) {
    if (it.qtype !== 'ESSAY' || it.earned != null) continue;
    const v = scores[String(it.seq)];
    if (v == null || Number.isNaN(v)) out[it.seq] = '请评分';
    else if (v < 0 || v > Number(it.score)) out[it.seq] = `须在 0 ~ ${Number(it.score)} 之间`;
  }
  return out;
}
