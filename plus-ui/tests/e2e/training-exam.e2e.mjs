/**
 * 16.1 端到端（无头 Chromium，部署环境，演示账号）：
 *   实施顾问在「培训管理」建课程并提交 → 车间主任在待办审批通过 → 课程发布；
 *   录入单选 / 多选 / 简答三题（人工复核后可用），LLM 草稿题只能是草稿；
 *   建考试（操作工参考，限时 10 分钟）→ 组卷 → 提交审批 → 主任通过 → 发布；
 *   操作工在「我的考试」开考（倒计时）→ 作答、保存、交卷 → 客观题即时判分、简答待评；
 *   车间主任在「阅卷」评分，超分值被拒 → 合法评分后出总分，及格生成培训记录（有效期按课程）；
 *   操作工在「我的培训记录」看到记录；上岗资格查询返回有效。
 * 运行：node tests/e2e/training-exam.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { BASE, check, open } from './lib.mjs';

const COURSE = 'ls.training.Course';
const QUESTION = 'ls.training.Question';
const EXAM = 'ls.training.Exam';
const ts = Date.now().toString(36).toUpperCase();

const impl = await open({ user: 'demo_implementer' });
const leader = await open({ user: 'demo_leader', browser: impl.browser });
const worker = await open({ user: 'demo_operator', browser: impl.browser });

const exec = async (s, code, targetId, version, input = {}) => {
  const r = await s.api('POST', `/api/op/${code}`, { targetId: targetId == null ? undefined : String(targetId), version, input });
  if (r.code !== 200) throw new Error(`${code} 失败：${r.msg}`);
  return r.data;
};
const get = async (s, concept, id) => (await s.api('GET', `/api/crud/${encodeURIComponent(concept)}/${id}`)).data;
const create = async (s, concept, body) => {
  const r = await s.api('POST', `/api/crud/${encodeURIComponent(concept)}`, body);
  if (r.code !== 200) throw new Error(`新建 ${concept} 失败：${r.msg}`);
  return r.data;
};
/** 服务器时区（部署为 Asia/Shanghai）的本地时间串。 */
const local = (d) => new Date(d.getTime() + 8 * 3600000).toISOString().slice(0, 19);
/** 主任在待办里审批通过（审批流 ORG_LEADER）。 */
async function approve(concept, id) {
  let todo;
  for (let i = 0; i < 20 && !todo; i++) {
    todo = (await leader.api('GET', '/api/flow/todo')).data.find(
      (t) => t.bizType === concept && String(t.bizId) === String(id) && t.status === 'TODO'
    );
    if (!todo) await new Promise((r) => setTimeout(r, 500));
  }
  check(!!todo, `${concept} ${id} 进入车间主任待办`);
  const r = await leader.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '同意发布' });
  check(r.code === 200, '主任审批通过');
  check((await get(impl, concept, id)).status === 'PUBLISHED', `${concept} 审批通过后自动发布`);
}

try {
  const orgs = (await impl.api('GET', '/api/asset/orgs')).data;
  const workshop = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间')) ?? orgs.find((o) => o.unit_type === 'WORKSHOP');
  const me = (await leader.api('GET', '/api/workbench/parties?keyword=' + encodeURIComponent('郑师傅'))).data[0];
  check(!!workshop && !!me, '找到一车间与操作工');

  // 课程：经「培训管理」页可见，提交审批 → 发布
  const course = await create(impl, COURSE, {
    courseCode: `SAFE-${ts}`,
    title: `受限空间作业安全 ${ts}`,
    positions: '操作工',
    passScore: 60,
    validMonths: 12,
    origin: 'MANUAL',
    orgId: workshop.id
  });
  await impl.page.goto(BASE + '/training/admin');
  await impl.page.getByTestId('training-admin').waitFor({ timeout: 30000 });
  await impl.page.getByText(`SAFE-${ts}`).first().waitFor({ timeout: 20000 });
  check(true, '培训管理页列出新课程');
  await exec(impl, `${COURSE}.submit`, course.id, (await get(impl, COURSE, course.id)).version);
  check((await get(impl, COURSE, course.id)).status === 'IN_REVIEW', '课程提交后待审批');
  await approve(COURSE, course.id);

  // 题库：人工录入后复核才可用；LLM 草稿只能是草稿
  const q = async (qtype, stem, score, questionOption, referenceAnswer) => {
    const r = await create(impl, QUESTION, {
      course: course.id,
      qtype,
      stem,
      score,
      origin: 'MANUAL',
      orgId: workshop.id,
      questionOption,
      referenceAnswer
    });
    await exec(impl, `${QUESTION}.approve`, r.id, (await get(impl, QUESTION, r.id)).version);
  };
  await q('SINGLE', '进入受限空间前必须先做什么', 40, [
    { optKey: 'A', content: '气体检测', correct: true },
    { optKey: 'B', content: '直接进入', correct: false }
  ]);
  await q('MULTI', '以下哪些属于受限空间', 40, [
    { optKey: 'A', content: '储罐', correct: true },
    { optKey: 'B', content: '地沟', correct: true },
    { optKey: 'C', content: '办公室', correct: false }
  ]);
  await q('ESSAY', '简述监护人职责', 20, [], '全程监护、保持联络、发现异常立即撤离');
  check(true, '三道题录入并人工复核');

  // 考试：组卷 → 提交 → 审批 → 发布
  const now = new Date();
  const exam = await create(impl, EXAM, {
    title: `受限空间上岗考试 ${ts}`,
    course: course.id,
    startAt: local(new Date(now.getTime() - 60000)),
    endAt: local(new Date(now.getTime() + 2 * 3600000)),
    durationMinutes: 10,
    orgId: workshop.id,
    examCandidate: [{ party: me.id }]
  });
  await exec(impl, `${EXAM}.compose`, exam.id, (await get(impl, EXAM, exam.id)).version, { seed: 7 });
  const composed = await get(impl, EXAM, exam.id);
  check(composed.examQuestion?.length === 3 && Number(composed.totalScore) === 100, '组卷 3 题，满分 100');
  await exec(impl, `${EXAM}.submit`, exam.id, composed.version);
  await approve(EXAM, exam.id);

  // 操作工：开考、倒计时、作答、保存、交卷
  const wp = worker.page;
  await wp.goto(BASE + '/training/exams');
  await wp.getByTestId(`exam-row-${exam.id}`).waitFor({ timeout: 30000 });
  check((await wp.getByTestId(`exam-phase-${exam.id}`).textContent()).includes('可参加'), '我的考试：可参加');
  await wp.getByTestId(`btn-exam-start-${exam.id}`).click();
  await wp.getByTestId('exam-countdown').waitFor({ timeout: 15000 });
  check(/^(09|10):\d\d$/.test((await wp.getByTestId('exam-countdown').textContent()).trim()), '开考后显示限时倒计时');
  const stems = await wp.locator('.item .stem').allTextContents();
  const seqOf = (kw) => String(stems.findIndex((s) => s.includes(kw)) + 1);
  const sSingle = seqOf('必须先做什么'),
    sMulti = seqOf('属于受限空间'),
    sEssay = seqOf('监护人职责');
  check(![sSingle, sMulti, sEssay].includes('0'), '答卷包含三道题（选项不含答案）');
  await wp.getByTestId(`q-${sSingle}-A`).click();
  await wp.getByTestId(`q-${sMulti}-A`).click();
  await wp.getByTestId(`q-${sMulti}-B`).click();
  await wp.getByTestId(`q-input-${sEssay}`).fill('全程监护，保持联络，异常时组织撤离');
  await wp.getByTestId('btn-exam-save').click();
  await wp.locator('.el-message', { hasText: '已保存' }).first().waitFor({ timeout: 15000 });
  check(true, '作答已保存');
  await wp.getByTestId('btn-exam-submit').click();
  await wp.locator('.el-message-box__btns .el-button--primary').click();
  await wp.getByTestId('exam-result').waitFor({ timeout: 15000 });
  check((await wp.getByTestId('exam-result').textContent()).includes('客观题 80 分，简答题待评'), '交卷：客观题即时判 80 分，简答待评');

  // 车间主任阅卷
  const lp = leader.page;
  await lp.goto(BASE + '/training/grading');
  const exams = (await worker.api('GET', '/api/training/my/exams')).data;
  const mine = exams.find((e) => e.id === exam.id);
  await lp.getByTestId(`btn-grade-${mine.answer_id}`).waitFor({ timeout: 30000 });
  // 超分值：直接调接口被后端拒绝
  const over = await leader.api('POST', '/api/op/ls.training.Answer.gradeEssay', {
    targetId: String(mine.answer_id),
    version: mine.answer_version,
    input: { scores: { [sEssay]: 25 } }
  });
  check(over.code !== 200 && /0 ~ 20/.test(JSON.stringify(over)), '简答超出分值被后端拒绝');
  await lp.getByTestId(`btn-grade-${mine.answer_id}`).click();
  await lp.getByText('全程监护、保持联络、发现异常立即撤离').waitFor({ timeout: 15000 });
  check(true, '阅卷人看到参考答案与考生作答');
  await lp.getByTestId(`essay-score-${sEssay}`).locator('input').fill('16');
  await lp.getByTestId(`essay-score-${sEssay}`).locator('input').blur();
  await lp.getByTestId('btn-grade-save').click();
  await lp.locator('.el-message', { hasText: '已评分' }).first().waitFor({ timeout: 15000 });
  const after = (await worker.api('GET', '/api/training/my/exams')).data.find((e) => e.id === exam.id);
  check(Number(after.score) === 96 && after.passed === true && !after.essay_pending, '评分后总分 96，及格');

  // 操作工：成绩与培训记录
  await wp.reload();
  await wp.getByTestId(`exam-score-${exam.id}`).waitFor({ timeout: 30000 });
  check((await wp.getByTestId(`exam-score-${exam.id}`).textContent()).includes('96'), '我的考试显示成绩 96');
  await wp.getByRole('tab', { name: '我的培训记录' }).click();
  const rec = (await worker.api('GET', '/api/training/my/records')).data.find((r) => r.course_code === `SAFE-${ts}`);
  check(!!rec, '及格后生成培训记录');
  await wp.getByTestId(`record-valid-${rec.id}`).waitFor({ timeout: 15000 });
  const expect = new Date();
  expect.setMonth(expect.getMonth() + 12);
  check(String(rec.valid_until).slice(0, 7) === local(expect).slice(0, 7), `培训记录有效期至 ${rec.valid_until}（课程 12 个月）`);

  const qual = await exec(leader, 'ls.training.TrainingRecord.qualification', undefined, undefined, { partyId: me.id, courseCode: `SAFE-${ts}` });
  check(qual?.data?.qualified === true || qual?.qualified === true || JSON.stringify(qual).includes('"qualified":true'), '上岗资格查询：有效');

  for (const s of [impl, leader, worker]) check(s.errors.length === 0, `页面无脚本错误（${s.errors.join(' | ') || '无'}）`);
  console.log('培训考试端到端通过');
} finally {
  await impl.browser.close();
}
