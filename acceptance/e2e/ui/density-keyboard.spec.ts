/**
 * 设计系统走查（ui/design-system）：紧凑密度的首屏行数、只用键盘完成一张审批单。
 */
import type { Page } from '@playwright/test';
import { expect, test, type Session } from '../support/walk.ts';

const COURSE = 'ls.training.Course';

async function workshop(s: Session) {
  const orgs = (await s.api('GET', '/api/asset/orgs')).body.data as any[];
  return orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
}
async function newCourse(s: Session, orgId: number, tag: string) {
  const ts = Date.now().toString(36).toUpperCase() + Math.random().toString(36).slice(2, 5).toUpperCase();
  const r = await s.api('POST', `/api/crud/${COURSE}`, { courseCode: `ACC-${tag}-${ts}`, title: `验收课程 ${tag} ${ts}`, positions: '操作工', passScore: 60, validMonths: 12, origin: 'MANUAL', orgId });
  expect(r.body.code, r.body.msg).toBe(200);
  return r.body.data;
}

test('[AC-UI-DS-004] 紧凑列表：1080p 屏幕以紧凑密度打开台账列表，首屏可见行数不少于 20 行', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  await walk.step('准备 25 条课程并把个人密度设为紧凑', async () => {
    const ws = await workshop(impl);
    for (let i = 0; i < 25; i++) await newCourse(impl, ws.id, 'DEN');
    const r = await impl.api('PUT', '/api/ui/prefs', { density: 'compact' });
    expect(r.body.code, r.body.msg).toBe(200);
  }, impl);
  await walk.step('1920×1080 打开课程台账：紧凑密度生效，首屏完整可见 ≥20 行', async () => {
    await impl.page.setViewportSize({ width: 1920, height: 1080 });
    await impl.page.goto(`/lsmom/ledger/${COURSE}`);
    await expect(impl.page.locator('html')).toHaveAttribute('data-density', 'compact');
    const rows = impl.page.locator('[data-testid="table"] .vxe-body--row');
    await expect(rows.nth(19)).toBeAttached({ timeout: 15_000 });
    // 只计入完整落在表体可视区（且在窗口内）的行
    const visible = await rows.evaluateAll((els) => els.filter((e) => {
      const b = e.getBoundingClientRect();
      const box = (e.closest('.vxe-table--body-wrapper') as HTMLElement).getBoundingClientRect();
      return b.height > 0 && b.top >= box.top && b.bottom <= Math.min(box.bottom, innerHeight) + 1;
    }).length);
    await test.info().attach('visible-rows.txt', { body: String(visible) });
    expect(visible, `首屏完整可见 ${visible} 行`).toBeGreaterThanOrEqual(20);
  }, impl);
  await impl.api('PUT', '/api/ui/prefs', { density: 'auto' });
});

/** 只按 Tab（或 Shift+Tab）把焦点移到满足条件的元素，最多按 max 次 */
async function tabTo(page: Page, match: string, opts: { back?: boolean; max?: number; text?: string } = {}) {
  for (let i = 0; i < (opts.max ?? 120); i++) {
    const hit = await page.evaluate(([sel, text]) => {
      const el = document.activeElement?.closest(sel);
      return !!el && (!text || (el.textContent ?? '').includes(text));
    }, [match, opts.text ?? ''] as const);
    if (hit) return;
    await page.keyboard.press(opts.back ? 'Shift+Tab' : 'Tab');
  }
  throw new Error(`按 Tab 到不了 ${match}`);
}

test('[AC-UI-DS-006] 键盘遍历：只用 Tab／Shift+Tab／Enter／Esc 完成一张审批单的填写意见、通过和关闭抽屉', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  const leader = await walk.as('acc_leader');
  const courses = await walk.step('实施顾问提交两门课程，车间主任有待办', async () => {
    const ws = await workshop(impl);
    const out: any[] = [];
    for (let i = 0; i < 2; i++) {
      const c = await newCourse(impl, ws.id, 'KB');
      const r = await impl.api('POST', `/api/op/${COURSE}.submit`, { targetId: String(c.id), version: c.version, input: {} });
      expect(r.body.code, r.body.msg).toBe(200);
      out.push(c);
    }
    return out;
  }, impl);
  expect(courses.length).toBe(2);
  const openTodos = async () => ((await leader.api('GET', '/api/flow/todo')).body.data as any[]).filter((x) => x.status === 'TODO').length;
  const before = await openTodos();
  const p = leader.page;
  await walk.step('首页：Tab 到"待我审核"中的一条，Enter 打开办理抽屉', async () => {
    await p.setViewportSize({ width: 1440, height: 900 });
    await p.goto('/index');
    await expect(p.getByTestId('home-card-ls.home.myReviews').getByTestId('home-row').first()).toBeVisible({ timeout: 15_000 });
    await p.locator('body').click({ position: { x: 1, y: 1 } }).catch(() => undefined);
    // 找课程审批：账号里可能还有别的待办（如须填写审核意见的借还单），卡片只列前几条
    await tabTo(p, '[data-testid="home-card-ls.home.myReviews"] [data-testid="home-row"]', { text: '课程发布审批' });
    await p.keyboard.press('Enter');
    await expect(p.getByTestId('task-panel').getByTestId('task-form')).toBeVisible();
  }, leader);
  await walk.step('Tab 到审批意见填写，再 Tab 到"通过"按 Enter', async () => {
    const panel = p.getByTestId('task-panel');
    await tabTo(p, '[data-testid="task-panel"] textarea');
    await p.keyboard.type('同意（键盘办理）');
    await tabTo(p, '[data-testid="task-panel"] [data-testid="btn-approve"]');
    await p.keyboard.press('Enter');
    await expect(p.locator('.el-message', { hasText: /已通过/ }).first()).toBeVisible();
    await expect(panel.getByTestId('task-form')).toBeVisible();
  }, leader);
  await walk.step('Esc 关闭抽屉，焦点回到页面', async () => {
    await p.keyboard.press('Escape');
    await expect(p.getByTestId('task-panel')).toBeHidden();
    // 键盘办理通过了一条：车间主任的待办恰好少一条
    await expect.poll(openTodos).toBe(before - 1);
  }, leader);
});
