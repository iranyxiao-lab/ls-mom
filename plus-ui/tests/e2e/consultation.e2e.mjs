/**
 * 16.2 端到端（无头 Chromium，部署环境，演示账号）：
 *   车间主任在「议题管理」可见新议题（匿名、范围一车间）并发布 → 操作工在「参与征求」提交意见，班组长也提交一条；
 *   答复人（车间主任）在「意见处理」看不到提交人，通用台账与时间轴也看不到；管理员（审计）在时间轴可见提交主体；
 *   主任答复一条、把安全意见转整改 → 生成关联该意见的 Issue，意见显示已转整改，操作工在「我的意见」看到；
 *   截止 → 草拟总结（LLM 草稿）→ 主任确认发布 → 操作工查看总结，观点可点开引用的来源意见。
 * 运行：node tests/e2e/consultation.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { BASE, check, open } from './lib.mjs';

const TOPIC = 'ls.consultation.Topic';
const OPINION = 'ls.consultation.Opinion';
const ts = Date.now().toString(36).toUpperCase();

const admin = await open();
const leader = await open({ user: 'demo_leader', browser: admin.browser });
const worker = await open({ user: 'demo_operator', browser: admin.browser });
const team = await open({ user: 'demo_team', browser: admin.browser });

const exec = async (s, code, targetId, version, input = {}) => {
  const r = await s.api('POST', `/api/op/${code}`, { targetId: targetId == null ? undefined : String(targetId), version, input });
  if (r.code !== 200) throw new Error(`${code} 失败：${r.msg}`);
  return r.data;
};
const get = async (s, concept, id) => (await s.api('GET', `/api/crud/${encodeURIComponent(concept)}/${id}`)).data;
/** 服务器时区（部署为 Asia/Shanghai）的本地时间串。 */
const local = (d) => new Date(d.getTime() + 8 * 3600000).toISOString().slice(0, 19);

try {
  const orgs = (await leader.api('GET', '/api/asset/orgs')).data;
  const workshop = orgs.find((o) => o.unit_type === 'WORKSHOP' && o.name.includes('一车间')) ?? orgs.find((o) => o.unit_type === 'WORKSHOP');
  const leaderParty = (await leader.api('GET', '/api/workbench/parties?keyword=')).data.find((p) => p.name.includes('主任'));
  check(!!workshop && !!leaderParty, '找到一车间与车间主任');

  // 议题：新建 → 议题管理页可见 → 发布
  const title = `安全隐患征集 ${ts}`;
  const created = await leader.api('POST', `/api/crud/${TOPIC}`, {
    title,
    description: '欢迎提出身边的安全隐患',
    scopeOrg: workshop.id,
    deadline: local(new Date(Date.now() + 2 * 3600000)),
    anonymous: true,
    owner: leaderParty.id,
    orgId: workshop.id
  });
  check(created.code === 200, `新建匿名议题（${created.msg}）`);
  const topicId = created.data.id;
  await leader.page.goto(BASE + '/consultation/admin');
  await leader.page.getByTestId('consultation-admin').waitFor({ timeout: 30000 });
  await leader.page.getByText(title).first().waitFor({ timeout: 20000 });
  check(true, '议题管理页列出新议题');
  await exec(leader, `${TOPIC}.open`, topicId, (await get(leader, TOPIC, topicId)).version);

  // 操作工经页面提交匿名意见；班组长经接口提交
  const wp = worker.page;
  await wp.goto(BASE + '/consultation/topics');
  await wp.getByTestId(`btn-opine-${topicId}`).click();
  check((await wp.getByTestId('anonymous-hint').textContent()).includes('看不到你的姓名'), '匿名议题提示答复人看不到姓名');
  await wp.getByTestId('opinion-content').fill('储罐区缺少洗眼器，发生喷溅无法及时冲洗');
  await wp.getByTestId('btn-opinion-save').click();
  await wp.locator('.el-message', { hasText: '意见已提交' }).first().waitFor({ timeout: 15000 });
  await wp.getByTestId(`topic-mine-${topicId}`).waitFor({ timeout: 15000 });
  check(true, '操作工提交匿名意见');
  const mine = (await worker.api('GET', `/api/consultation/my/opinions?topicId=${topicId}`)).data;
  const o1 = mine[0].id;
  const r2 = await exec(team, 'ls.consultation.Opinion.submit', undefined, undefined, {
    topicId,
    content: '三号泵房夜间照明不足',
    category: 'SAFETY'
  });
  const o2 = r2.data?.opinionId ?? r2.opinionId;
  check(!!o1 && !!o2, '两条意见已提交');

  // 答复人看不到提交人：页面、通用台账、时间轴
  const lp = leader.page;
  await lp.goto(BASE + '/consultation/handle');
  await lp.getByTestId(`handle-topic-${topicId}`).click();
  await lp.getByTestId(`op-author-${o1}`).waitFor({ timeout: 20000 });
  check((await lp.getByTestId(`op-author-${o1}`).textContent()).trim().startsWith('匿名'), '意见处理页显示“匿名”');
  const raw = await get(leader, OPINION, o1);
  check(raw && !('submitter' in raw) && raw.author == null && String(raw.createdBy) === '0', '通用台账读不到提交主体');
  const tlLeader = (await leader.api('GET', `/api/timeline/${OPINION}/${o1}`)).data;
  const sub = tlLeader.find((e) => e.type === 'ls.consultation.Opinion.submit');
  check(sub && sub.actorParty == null && sub.actorName === '（匿名）', '答复人看到的时间轴不含提交人');
  const tlAdmin = (await admin.api('GET', `/api/timeline/${OPINION}/${o1}`)).data;
  const subA = tlAdmin.find((e) => e.type === 'ls.consultation.Opinion.submit');
  check(subA && subA.actorName && subA.actorName !== '（匿名）', `审计（管理员）可见提交主体：${subA?.actorName}`);

  // 答复一条，安全意见转整改
  await lp.getByTestId(`btn-reply-${o2}`).click();
  await lp.getByTestId('reply-text').fill('已安排电工本周加装照明');
  await lp.getByTestId('btn-reply-save').click();
  await lp.locator('.el-message', { hasText: '已答复' }).first().waitFor({ timeout: 15000 });
  await lp.getByTestId(`op-status-${o2}`).filter({ hasText: '已答复' }).waitFor({ timeout: 15000 });
  check(true, '答复意见');
  await lp.getByTestId(`btn-to-issue-${o1}`).click();
  await lp.getByTestId('issue-sev-HIGH').click();
  await lp.getByTestId('btn-issue-save').click();
  await lp.locator('.el-message', { hasText: '已转整改' }).first().waitFor({ timeout: 15000 });
  await lp.getByTestId(`op-status-${o1}`).filter({ hasText: '已转整改' }).waitFor({ timeout: 15000 });
  const conv = (await leader.api('GET', `/api/consultation/topics/${topicId}/opinions`)).data.find((o) => o.id === o1);
  check(!!conv.issue_code && (await lp.getByTestId(`op-issue-${o1}`).textContent()).includes(conv.issue_code), `转整改生成 Issue ${conv.issue_code}`);
  const op = await get(leader, OPINION, o1);
  check(Number(op.issue) === Number(conv.issue_id) && op.status === 'CONVERTED', 'Issue 关联该意见，意见状态已转整改');
  const myAfter = (await worker.api('GET', `/api/consultation/my/opinions?topicId=${topicId}`)).data.find((o) => o.id === o1);
  check(myAfter.status === 'CONVERTED' && myAfter.issue_code === conv.issue_code, '提交人在「我的意见」看到已转整改');

  // 截止 → 总结草稿（LLM）→ 确认发布
  await lp.getByTestId('btn-topic-close').click();
  await lp.locator('.el-message', { hasText: '已截止' }).first().waitFor({ timeout: 15000 });
  const d = await exec(leader, 'ls.consultation.Summary.draft', undefined, undefined, {
    topicId,
    origin: 'LLM',
    content: '共收到 2 条意见，集中在应急设施与照明。',
    points: [
      { viewpoint: '储罐区需配备洗眼器', opinionIds: [o1] },
      { viewpoint: '泵房夜间照明需加强', opinionIds: [o2] }
    ]
  });
  check(!!(d.data?.summaryId ?? d.summaryId), 'LLM 草拟总结为草稿');
  await lp.reload();
  await lp.getByTestId(`handle-topic-${topicId}`).click();
  await lp.getByTestId('summary-status').waitFor({ timeout: 20000 });
  check((await lp.getByTestId('summary-status').textContent()).includes('草稿待确认'), '负责人看到待确认的总结草稿');
  await lp.getByTestId('btn-summary-publish').click();
  await lp.locator('.el-message', { hasText: '总结已发布' }).first().waitFor({ timeout: 15000 });

  await wp.reload();
  await wp.getByTestId(`btn-summary-${topicId}`).click();
  await wp.getByTestId('summary-point-1').waitFor({ timeout: 20000 });
  check((await wp.getByTestId('summary-status').textContent()).includes('已发布'), '员工看到已发布总结');
  await wp.getByTestId(`cite-1-${o1}`).click();
  await wp.getByTestId(`cite-content-${o1}`).waitFor({ timeout: 10000 });
  check((await wp.getByTestId(`cite-content-${o1}`).textContent()).includes('洗眼器'), '观点链接到引用的来源意见');

  for (const s of [admin, leader, worker, team]) check(s.errors.length === 0, `页面无脚本错误（${s.errors.join(' | ') || '无'}）`);
  console.log('意见咨询端到端通过');
} finally {
  await admin.browser.close();
}
