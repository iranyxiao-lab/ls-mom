/**
 * 旅程 J-D-PACKS：培训考试与意见征求 Pack（切片 D）。
 * 培训：实施顾问（陈顾问）建课程、题库、组卷并提交审批；车间主任（周主任）审批发布、阅卷；操作工（郑师傅）参加考试，及格后生成培训记录与上岗资格。
 * 意见征求：车间主任发布匿名议题；操作工与班长提交意见；车间主任答复、转整改、确认 LLM 总结草稿；员工看到已发布总结。
 * 负向：简答超分值被拒、操作工审批课程（非办理人）被拒、答复人看不到匿名提交人、二车间主任读取议题被拒、LLM 总结未确认前员工看不到。
 * 一致性：课程状态、流程实例、待办、审计、事件与 trace_id。
 */
import { consistency } from '../support/consistency.ts';
import { create, denied, get, ok, op, until } from '../support/ops.ts';
import { expect, test, type Session } from '../support/walk.ts';

const COURSE = 'ls.training.Course';
const QUESTION = 'ls.training.Question';
const EXAM = 'ls.training.Exam';
const TOPIC = 'ls.consultation.Topic';
const OPINION = 'ls.consultation.Opinion';

const pad = (n: number) => String(n).padStart(2, '0');
const local = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;

async function approve(leader: Session, concept: string, id: number) {
  const todo = await until(
    async () => ((await leader.api('GET', '/api/flow/todo')).body.data as any[]).find((x) => x.bizType === concept && String(x.bizId) === String(id) && x.status === 'TODO'),
    `${concept}#${id} 的审批待办`
  );
  const r = await leader.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '同意发布（验收）' });
  ok(r, '审批通过');
  return { todo, traceId: r.traceId };
}

test('[AC-J-D-PACKS-001] 旅程：培训考试与意见征求', async ({ walk }) => {
  const impl = await walk.as('acc_implementer');
  const leader = await walk.as('acc_leader');
  const worker = await walk.as('acc_operator');
  const team = await walk.as('acc_team');
  const leaderB = await walk.as('acc_leader_b');
  const admin = await walk.as('acc_admin');
  const ts = Date.now().toString(36).toUpperCase();
  const orgs = ok<any[]>(await impl.api('GET', '/api/asset/orgs'), '组织');
  const workshop = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间'));
  const me = ok<any>(await worker.api('GET', '/api/flow/me'), '操作工身份').partyId;

  const course = await walk.step('实施顾问：新建课程并提交发布审批', async () => {
    const c = await create(impl, COURSE, { courseCode: `ACC-SAFE-${ts}`, title: `受限空间作业安全（验收 ${ts}）`, positions: '操作工', passScore: 60, validMonths: 12, origin: 'MANUAL', orgId: workshop.id });
    await op(impl, `${COURSE}.submit`, c.id);
    await impl.page.goto('/training/admin');
    return c;
  }, impl);

  const published = await walk.step('负向：操作工办理课程审批被拒；车间主任审批通过，课程发布', async () => {
    const todo = await until(
      async () => ((await leader.api('GET', '/api/flow/todo')).body.data as any[]).find((x) => x.bizType === COURSE && String(x.bizId) === String(course.id)),
      '课程审批待办'
    );
    await denied(worker.api('POST', `/api/flow/tasks/${todo.taskId}/complete`, { action: 'APPROVE', comment: '越权' }), '非办理人审批');
    const r = await approve(leader, COURSE, course.id);
    expect((await get(impl, COURSE, course.id)).status).toBe('PUBLISHED');
    return r;
  }, leader);

  await walk.step('跨模块一致性：课程状态、流程、待办、审计、事件、trace_id', async () => {
    const r = await consistency(admin, COURSE, course.id, { status: 'PUBLISHED', flowStatus: 'APPROVED', timeline: /通过|发布|PUBLISHED/, auditOp: `${COURSE}.publish`, traceId: published.traceId });
    await test.info().attach('consistency.json', { body: JSON.stringify(r.checks, null, 2), contentType: 'application/json' });
    expect(r.failed, r.failed.map((c) => `${c.name}：${c.detail}`).join('；')).toEqual([]);
  }, admin);

  const exam = await walk.step('实施顾问：录入并复核三道题，组卷提交；车间主任审批发布', async () => {
    const q = async (qtype: string, stem: string, score: number, questionOption: unknown[], referenceAnswer?: string) => {
      const r = await create(impl, QUESTION, { course: course.id, qtype, stem, score, origin: 'MANUAL', orgId: workshop.id, questionOption, referenceAnswer });
      await op(impl, `${QUESTION}.approve`, r.id);
    };
    await q('SINGLE', '进入受限空间前必须先做什么', 40, [{ optKey: 'A', content: '气体检测', correct: true }, { optKey: 'B', content: '直接进入', correct: false }]);
    await q('MULTI', '以下哪些属于受限空间', 40, [{ optKey: 'A', content: '储罐', correct: true }, { optKey: 'B', content: '地沟', correct: true }, { optKey: 'C', content: '办公室', correct: false }]);
    await q('ESSAY', '简述监护人职责', 20, [], '全程监护、保持联络、发现异常立即撤离');
    const now = Date.now();
    const e = await create(impl, EXAM, { title: `受限空间上岗考试（验收 ${ts}）`, course: course.id, startAt: local(new Date(now - 60_000)), endAt: local(new Date(now + 2 * 3600_000)), durationMinutes: 10, orgId: workshop.id, examCandidate: [{ party: me }] });
    await op(impl, `${EXAM}.compose`, e.id, { seed: 7 });
    expect(Number((await get(impl, EXAM, e.id)).totalScore)).toBe(100);
    await op(impl, `${EXAM}.submit`, e.id);
    await approve(leader, EXAM, e.id);
    return e;
  }, impl);

  const sEssay = await walk.step('操作工：开考、作答、交卷，客观题即时判分', async () => {
    const wp = worker.page;
    await wp.goto('/training/exams');
    await wp.getByTestId(`btn-exam-start-${exam.id}`).click({ timeout: 30_000 });
    await wp.getByTestId('exam-countdown').waitFor({ timeout: 15_000 });
    const stems = await wp.locator('.item .stem').allTextContents();
    const seq = (kw: string) => String(stems.findIndex((s) => s.includes(kw)) + 1);
    await wp.getByTestId(`q-${seq('必须先做什么')}-A`).click();
    await wp.getByTestId(`q-${seq('属于受限空间')}-A`).click();
    await wp.getByTestId(`q-${seq('属于受限空间')}-B`).click();
    await wp.getByTestId(`q-input-${seq('监护人职责')}`).fill('全程监护，保持联络，异常时组织撤离');
    await wp.getByTestId('btn-exam-submit').click();
    await wp.locator('.el-message-box__btns .el-button--primary').click();
    await expect(wp.getByTestId('exam-result')).toContainText('客观题 80 分', { timeout: 15_000 });
    return seq('监护人职责');
  }, worker);

  await walk.step('车间主任阅卷；负向：简答超分值被拒；及格后生成培训记录与上岗资格', async () => {
    const mine = ok<any[]>(await worker.api('GET', '/api/training/my/exams'), '我的考试').find((e) => e.id === exam.id);
    await denied(leader.api('POST', '/api/op/ls.training.Answer.gradeEssay', { targetId: String(mine.answer_id), version: mine.answer_version, input: { scores: { [sEssay]: 25 } } }), '简答超分值', /0 ~ 20|分值/);
    await op(leader, 'ls.training.Answer.gradeEssay', mine.answer_id, { scores: { [sEssay]: 16 } }, mine.answer_version);
    const after = ok<any[]>(await worker.api('GET', '/api/training/my/exams'), '我的考试').find((e) => e.id === exam.id);
    expect(Number(after.score)).toBe(96);
    expect(after.passed).toBe(true);
    const rec = ok<any[]>(await worker.api('GET', '/api/training/my/records'), '培训记录').find((r) => r.course_code === `ACC-SAFE-${ts}`);
    expect(rec, '生成培训记录').toBeTruthy();
    const qual = ok<any>(await leader.api('POST', '/api/op/ls.training.TrainingRecord.qualification', { input: { partyId: me, courseCode: `ACC-SAFE-${ts}` } }), '上岗资格');
    expect(JSON.stringify(qual)).toContain('"qualified":true');
    await leader.page.goto('/training/grading');
  }, leader);

  const topic = await walk.step('车间主任：发布匿名意见征求', async () => {
    const owner = ok<any>(await leader.api('GET', '/api/flow/me'), '身份').partyId;
    const t = await create(leader, TOPIC, { title: `身边的安全隐患（验收 ${ts}）`, description: '欢迎提出身边的安全隐患', scopeOrg: workshop.id, deadline: local(new Date(Date.now() + 2 * 3600_000)), anonymous: true, owner, orgId: workshop.id });
    await op(leader, `${TOPIC}.open`, t.id);
    return t;
  }, leader);

  const [o1, o2] = await walk.step('操作工与班长提交意见；负向：二车间主任读取议题被拒', async () => {
    const sub = async (s: Session, content: string, category: string) => {
      const r = ok<any>(await s.api('POST', '/api/op/ls.consultation.Opinion.submit', { input: { topicId: topic.id, content, category } }), '提交意见');
      return Number(r.data?.opinionId ?? r.opinionId);
    };
    const a = await sub(worker, '储罐区缺少洗眼器（验收）', 'SAFETY');
    const b = await sub(team, '泵房夜间照明不足（验收）', 'SAFETY');
    await denied(leaderB.api('GET', `/api/crud/${TOPIC}/${topic.id}`), '跨车间读取议题');
    await worker.page.goto('/consultation/topics');
    return [a, b];
  }, worker);

  await walk.step('车间主任：看不到匿名提交人；答复一条、另一条转整改', async () => {
    const raw = await get(leader, OPINION, o1);
    expect(raw.author ?? null, '答复人读不到提交主体').toBeNull();
    const tl = ok<any[]>(await leader.api('GET', `/api/timeline/${OPINION}/${o1}`), '时间轴');
    expect(tl.find((e) => e.type === 'ls.consultation.Opinion.submit')?.actorName).toBe('（匿名）');
    await op(leader, 'ls.consultation.Opinion.reply', o2, { reply: '已安排电工本周加装照明' });
    await op(leader, 'ls.consultation.Opinion.toIssue', o1, { severity: 'HIGH' });
    const mine = ok<any[]>(await worker.api('GET', `/api/consultation/my/opinions?topicId=${topic.id}`), '我的意见').find((o) => o.id === o1);
    expect(mine.status).toBe('CONVERTED');
    await leader.page.goto('/consultation/handle');
  }, leader);

  await walk.step('车间主任截止征求；LLM 草拟总结：确认前员工看不到；确认发布后员工可见', async () => {
    await op(leader, `${TOPIC}.close`, topic.id);
    const d = ok<any>(await leader.api('POST', '/api/op/ls.consultation.Summary.draft', { input: { topicId: topic.id, origin: 'LLM', content: '共收到 2 条意见，集中在应急设施与照明。', points: [{ viewpoint: '储罐区需配备洗眼器', opinionIds: [o1] }, { viewpoint: '泵房夜间照明需加强', opinionIds: [o2] }] } }), '总结草稿');
    const sid = Number(d.data?.summaryId ?? d.summaryId);
    const seen = async () => (await worker.api('GET', `/api/consultation/topics/${topic.id}/summary`)).body;
    const before = await seen();
    expect(before.code !== 200 || before.data == null || before.data.status !== 'DRAFT', '草稿未确认前员工看不到总结').toBe(true);
    await op(leader, 'ls.consultation.Summary.publish', sid);
    const after = await seen();
    expect(after.code, after.msg).toBe(200);
    expect(JSON.stringify(after.data)).toContain('洗眼器');
    await worker.page.goto('/consultation/topics');
  }, leader);
});
