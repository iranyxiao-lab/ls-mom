/**
 * 16.1：培训考试——限时作答倒计时、多选作答格式、交卷、到时禁止编辑、简答评分范围校验。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import MyExams from '@/views/lsmom/training/exams/MyExams.vue';
import GradingBoard from '@/views/lsmom/training/grading/GradingBoard.vue';
import { essayErrors, examPhase, fmtCountdown, joinKeys, parseChoices, remainingSeconds } from '@/api/lsmom/trainingModel';

const iso = (ms: number) => new Date(ms).toISOString();

function sheet(over: Record<string, any> = {}) {
  return {
    id: 9,
    version: 1,
    status: 'IN_PROGRESS',
    examTitle: '安全规程考试',
    candidateName: '王工',
    deadline: iso(Date.now() + 10 * 60_000),
    items: [
      {
        seq: 1,
        qtype: 'SINGLE',
        stem: '动火作业需要？',
        score: 40,
        choices: JSON.stringify([
          { key: 'A', content: '审批' },
          { key: 'B', content: '不需要' }
        ])
      },
      {
        seq: 2,
        qtype: 'MULTI',
        stem: '个人防护包括？',
        score: 40,
        choices: JSON.stringify([
          { key: 'A', content: '安全帽' },
          { key: 'B', content: '手套' },
          { key: 'C', content: '拖鞋' }
        ])
      },
      { seq: 3, qtype: 'ESSAY', stem: '简述受限空间作业要点', score: 20, choices: null }
    ],
    ...over
  };
}

function makeApi(over: Record<string, any> = {}) {
  return {
    myExams: vi.fn(async () => [] as any[]),
    myRecords: vi.fn(async () => [] as any[]),
    answer: vi.fn(async () => sheet()),
    grading: vi.fn(async () => [] as any[]),
    start: vi.fn(async () => ({ targetId: '', version: 0, status: 'OK', warnings: [], data: { answerId: 9 } })),
    save: vi.fn(async () => ({ targetId: '9', version: 2, status: 'OK', warnings: [] })),
    submit: vi.fn(async () => ({ targetId: '9', version: 2, status: 'OK', warnings: [] })),
    gradeEssay: vi.fn(async () => ({ targetId: '9', version: 3, status: 'OK', warnings: [] })),
    ...over
  };
}

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper, .el-message, .el-popper, .el-message-box__wrapper').forEach((e) => e.remove());
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}
const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

async function mount(comp: Component, api: any) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(comp, { ref: r, api }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return r;
}

describe('培训模型', () => {
  it('倒计时、多选键、选项解析、考试阶段', () => {
    const now = Date.parse('2026-11-10T08:00:00Z');
    expect(remainingSeconds('2026-11-10T08:01:05Z', now)).toBe(65);
    expect(remainingSeconds('2026-11-10T07:00:00Z', now)).toBe(0);
    expect(fmtCountdown(65)).toBe('01:05');
    expect(joinKeys(['C', 'A', 'A'])).toBe('A,C');
    expect(parseChoices('[{"key":"A","content":"x"}]')).toEqual([{ key: 'A', content: 'x' }]);
    expect(parseChoices('bad')).toEqual([]);
    const base = { id: 1, title: '', status: 'PUBLISHED', start_at: '2026-11-10T07:00:00Z', end_at: '2026-11-10T09:00:00Z' } as any;
    expect(examPhase(base, now)).toBe('OPEN');
    expect(examPhase({ ...base, start_at: '2026-11-10T08:30:00Z' }, now)).toBe('NOT_STARTED');
    expect(examPhase({ ...base, answer_status: 'GRADED', essay_pending: true }, now)).toBe('PENDING');
    expect(examPhase({ ...base, end_at: '2026-11-10T07:30:00Z' }, now)).toBe('MISSED');
    // 以服务器当前时间判断阶段，不受浏览器时区影响
    expect(examPhase({ ...base, start_at: '2026-11-10 10:00:00', end_at: '2026-11-10 12:00:00', server_now: '2026-11-10T09:59:00' })).toBe(
      'NOT_STARTED'
    );
    expect(examPhase({ ...base, start_at: '2026-11-10 10:00:00', end_at: '2026-11-10 12:00:00', server_now: '2026-11-10T10:01:00' })).toBe('OPEN');
  });

  it('简答评分：未评、超出分值都报错', () => {
    const items = sheet().items as any[];
    expect(essayErrors(items, {})).toEqual({ 3: '请评分' });
    expect(essayErrors(items, { 3: 25 })).toEqual({ 3: '须在 0 ~ 20 之间' });
    expect(essayErrors(items, { 3: 15 })).toEqual({});
  });
});

describe('我的考试', () => {
  it('开考后显示倒计时；作答单选与多选后交卷，多选按排序后的键提交', async () => {
    const api = makeApi({
      myExams: vi.fn(async () => [
        {
          id: 5,
          title: '安全规程考试',
          status: 'PUBLISHED',
          start_at: iso(Date.now() - 60_000),
          end_at: iso(Date.now() + 3600_000),
          duration_minutes: 30,
          total_score: 100,
          course_title: '安全规程',
          pass_score: 60
        }
      ])
    });
    await mount(MyExams, api);
    expect(q('exam-phase-5')!.textContent).toContain('可参加');
    q('btn-exam-start-5')!.click();
    await flush(200);
    expect(api.start).toHaveBeenCalledWith(5);
    expect(q('exam-countdown')!.textContent).toMatch(/^(09|10):\d\d$/);
    (q('q-1-A')!.querySelector('input') as HTMLInputElement).click();
    (q('q-2-B')!.querySelector('input') as HTMLInputElement).click();
    await flush();
    (q('q-2-A')!.querySelector('input') as HTMLInputElement).click();
    await flush();
    q('btn-exam-save')!.click();
    await flush(150);
    expect(api.save).toHaveBeenCalledWith(9, 1, { 1: 'A', 2: 'A,B' });
    q('btn-exam-submit')!.click();
    await flush(200);
    expect(document.body.textContent).toContain('还有 1 题未作答');
    (document.querySelector('.el-message-box__btns .el-button--primary') as HTMLElement).click();
    await flush(200);
    expect(api.submit).toHaveBeenCalledWith(9, 2, { 1: 'A', 2: 'A,B' });
  });

  it('时间已到（以服务器剩余秒数为准）：不可编辑、不显示交卷按钮', async () => {
    // 截止时间按本机看还有 1 小时，但服务器说已到时
    const api = makeApi({ answer: vi.fn(async () => sheet({ deadline: iso(Date.now() + 3600_000), remainingSeconds: 0 })) });
    const page = await mount(MyExams, api);
    await page.value.openSheet(9);
    await flush(150);
    expect(q('exam-countdown')!.textContent).toBe('00:00');
    expect(q('btn-exam-submit')).toBeNull();
    const ta = q('q-input-3')!;
    expect(((ta.tagName === 'TEXTAREA' ? ta : ta.querySelector('textarea')) as HTMLTextAreaElement).readOnly).toBe(true);
  });

  it('已出分：显示总分与及格', async () => {
    const graded = sheet({ status: 'GRADED', totalScore: 80, passed: true, essayPending: false });
    graded.items = graded.items.map((i: any) => ({ ...i, earned: i.score, correct: true }));
    const api = makeApi({ answer: vi.fn(async () => graded) });
    const page = await mount(MyExams, api);
    await page.value.openSheet(9);
    await flush(150);
    expect(q('exam-result-score')!.textContent).toBe('80');
    expect(q('exam-result')!.textContent).toContain('及格');
    expect(q('q-earned-1')!.textContent).toContain('40');
  });
});

describe('阅卷', () => {
  it('超出分值前端拦下；合法分数提交 gradeEssay', async () => {
    const graded = sheet({ status: 'GRADED', essayPending: true, version: 4 });
    graded.items = graded.items.map((i: any) =>
      i.qtype === 'ESSAY' ? { ...i, response: '先检测气体', referenceAnswer: '通风、检测、监护' } : { ...i, earned: i.score }
    );
    const api = makeApi({
      grading: vi.fn(async () => [{ id: 9, version: 4, exam_title: '安全规程考试', candidate_name: '王工', objective_score: 80 }]),
      answer: vi.fn(async () => graded)
    });
    await mount(GradingBoard, api);
    q('btn-grade-9')!.click();
    await flush(200);
    expect(document.body.textContent).toContain('通风、检测、监护');
    q('btn-grade-save')!.click();
    await flush();
    expect(document.body.textContent).toContain('请评分');
    expect(api.gradeEssay).not.toHaveBeenCalled();
    const input = q('essay-score-3')!.querySelector('input') as HTMLInputElement;
    input.value = '15';
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('change'));
    await flush();
    q('btn-grade-save')!.click();
    await flush(150);
    expect(api.gradeEssay).toHaveBeenCalledWith(9, 4, { 3: 15 });
  });
});
