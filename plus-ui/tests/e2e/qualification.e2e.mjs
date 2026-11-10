/**
 * modernize-ui-ux 6.5 端到端（无头 Chromium，部署环境，演示账号）：可操作的错误提示。
 *   实施顾问建课程「动火作业安全」（适用作业：动火关键词）并经车间主任审批发布；
 *   郑师傅的培训记录已过期、吴班长的有效；
 *   车间主任在分派台把标题含关键词的工作分派给郑师傅 → 被拒：
 *     提示"郑师傅的…作业资质已于 M 月 D 日过期"，说明原因，提供「改派他人」「查看培训记录」，技术详情折叠并附追踪编号；
 *   点「改派他人」→ 郑师傅从人员里去掉、光标回到人员选择 → 选吴班长 → 分派成功；
 *   再试一次分派给郑师傅，点「查看培训记录」→ 打开该培训记录。
 * 运行：node tests/e2e/qualification.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { BASE, check, open } from './lib.mjs';

const COURSE = 'ls.training.Course';
const RECORD = 'ls.training.TrainingRecord';
const ts = Date.now().toString(36).toUpperCase();
const kw = `动火${ts.slice(-4)}`;

const admin = await open({ user: 'admin' });
const impl = await open({ user: 'demo_implementer', browser: admin.browser });
const leader = await open({ user: 'demo_leader', browser: admin.browser });

const ok = (r, what) => {
  if (r.code !== 200) throw new Error(`${what} 失败：${r.msg}`);
  return r.data;
};
const get = async (s, concept, id) => (await s.api('GET', `/api/crud/${encodeURIComponent(concept)}/${id}`)).data;
const party = async (name) => (await leader.api('GET', '/api/workbench/parties?keyword=' + encodeURIComponent(name))).data.find((p) => p.name.startsWith(name));
const day = (offset) => {
  const d = new Date(Date.now() + 8 * 3600000 + offset * 864e5);
  return d.toISOString().slice(0, 10);
};

try {
  const orgs = (await impl.api('GET', '/api/asset/orgs')).data;
  const workshop = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
  const team = orgs.find((o) => o.unit_type === 'TEAM' && o.name.includes('一车间甲班'));
  const zheng = await party('郑师傅');
  const wu = await party('吴班长');
  check(!!workshop && !!team && !!zheng && !!wu, '找到一车间、甲班与人员');

  // 课程：适用作业含关键词，审批发布
  const course = ok(
    await impl.api('POST', `/api/crud/${COURSE}`, {
      courseCode: `HOT-${ts}`,
      title: `动火作业安全 ${ts}`,
      positions: '操作工',
      workKeywords: `${kw}，登高`,
      passScore: 60,
      validMonths: 12,
      origin: 'MANUAL',
      orgId: workshop.id
    }),
    '新建课程'
  );
  ok(await impl.api('POST', `/api/op/${COURSE}.submit`, { targetId: String(course.id), version: (await get(impl, COURSE, course.id)).version, input: {} }), '提交课程');
  let todo;
  for (let i = 0; i < 20 && !todo; i++) {
    todo = (await leader.api('GET', '/api/flow/todo')).data.find((t) => t.bizType === COURSE && String(t.bizId) === String(course.id) && t.status === 'TODO');
    if (!todo) await new Promise((r) => setTimeout(r, 500));
  }
  ok(await leader.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '同意发布' }), '审批课程');
  check((await get(impl, COURSE, course.id)).status === 'PUBLISHED', '课程已发布');

  // 培训记录：郑师傅过期、吴班长有效
  const expiredOn = day(-5);
  const old = ok(
    await admin.api('POST', `/api/crud/${RECORD}`, { party: zheng.id, course: course.id, score: 80, passedOn: day(-370), validUntil: expiredOn, orgId: team.id }),
    '建过期培训记录'
  );
  ok(await admin.api('POST', `/api/crud/${RECORD}`, { party: wu.id, course: course.id, score: 90, passedOn: day(-30), validUntil: day(330), orgId: team.id }), '建有效培训记录');

  // 工作：标题含关键词
  const title = `${kw}：焊补 3 号管道 ${ts}`;
  const work = ok(
    await admin.api('POST', '/api/op/ls.core.Work.create', { input: { workType: 'PRODUCTION', title, orgUnitId: team.id, priority: 4, plannedStart: `${day(1)}T08:00:00`, plannedEnd: `${day(1)}T16:00:00` } }),
    '新建工作'
  );
  const workId = work.targetId;

  // 分派台：分派给郑师傅 → 被拒，可操作的错误提示
  const p = leader.page;
  await p.goto(BASE + '/production/dispatch');
  await p.getByTestId(`dispatch-row-${workId}`).click({ timeout: 30000 });
  const partyBox = () => p.locator('.el-select:has([data-testid="dispatch-party"]), .el-select[data-testid="dispatch-party"]').first();
  const pick = async (name) => {
    // 人员多选（远程搜索）：点开选择框后直接键入
    await partyBox().locator('.el-select__wrapper').click();
    await p.keyboard.type(name, { delay: 60 });
    await p.locator('.el-select-dropdown__item:visible', { hasText: name }).first().click();
    // 多选选完后下拉不收起：点标题收起
    await p.getByTestId('dispatch-panel').locator('h4', { hasText: '分派' }).click();
    await p.waitForTimeout(200);
  };
  await pick('郑师傅');
  await p.getByTestId('btn-assign').click();
  const notice = p.locator('.ls-error-notice').last();
  await notice.waitFor({ timeout: 15000 });
  const m = Number(expiredOn.slice(5, 7));
  const d = Number(expiredOn.slice(8, 10));
  const text = await notice.textContent();
  check(text.includes(`${zheng.name}的${kw}作业资质已于 ${m} 月 ${d} 日过期`), '提示谁的什么资质何时过期：' + text);
  check((await notice.getByTestId('error-why').textContent()).includes(`属于${kw}作业`), '说明为什么');
  check((await notice.getByTestId('error-fix-reassign').textContent()) === '改派他人', '提供「改派他人」');
  check((await notice.getByTestId('error-fix-link').textContent()) === '查看培训记录', '提供「查看培训记录」');
  const tech = notice.getByTestId('error-tech');
  check(!(await tech.evaluate((el) => el.open)), '技术详情默认折叠');
  check(/追踪编号：\S+/.test(await tech.textContent()), '技术详情附追踪编号');
  check(!/Exception|at com\./.test(text), '不展示异常堆栈');
  check((await leader.api('GET', `/api/production/board?from=${day(-30)}&to=${day(30)}`)).data.find((w) => String(w.id) === String(workId))?.status !== 'ASSIGNED', '被拒后工作未分派');

  // 改派他人：郑师傅从人员里去掉，光标回到人员选择，选吴班长后分派成功
  await notice.getByTestId('error-fix-reassign').click();
  await p.waitForTimeout(300);
  check(!(await partyBox().textContent()).includes('郑师傅'), '改派：已去掉郑师傅');
  check(await partyBox().evaluate((el) => el.contains(document.activeElement)), '改派：光标回到人员选择');
  await pick('吴班长');
  await p.getByTestId('btn-assign').click();
  let st = '';
  for (let i = 0; i < 30 && st !== 'ASSIGNED'; i++) {
    st = (await leader.api('GET', `/api/production/board?from=${day(-30)}&to=${day(30)}`)).data.find((w) => String(w.id) === String(workId))?.status;
    if (st !== 'ASSIGNED') await p.waitForTimeout(500);
  }
  check(st === 'ASSIGNED', '改派给资质有效的吴班长后分派成功');

  // 查看培训记录：打开郑师傅那条过期记录
  await pick('郑师傅');
  await p.getByTestId('btn-assign').click();
  await p.locator('.ls-error-notice').last().getByTestId('error-fix-link').click();
  await p.waitForURL(new RegExp(`/lsmom/ledger/${RECORD.replace(/\./g, '\\.')}`), { timeout: 15000 });
  await p.getByTestId('ledger-detail').waitFor({ timeout: 15000 });
  let detail = '';
  for (let i = 0; i < 30 && !detail.includes(expiredOn); i++) {
    detail = (await p.getByTestId('ledger-detail').textContent()) ?? '';
    if (!detail.includes(expiredOn)) await p.waitForTimeout(300);
  }
  check(detail.includes(expiredOn) && detail.includes(`HOT-${ts}`) && detail.includes("郑师傅"), '查看培训记录：打开郑师傅那条过期记录：' + detail.slice(0, 400));
  for (const s of [leader]) check(s.errors.length === 0, '页面无脚本错误：' + s.errors.join('；'));
} finally {
  await admin.browser.close();
}
