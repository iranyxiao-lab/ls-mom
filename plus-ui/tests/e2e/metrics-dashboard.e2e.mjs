/**
 * 11.1 / 11.2 部署环境实测（无头）：内置指标与命名 Query、重复口径、参数越界、看板含 SQL 被拒、看板渲染与钻取。
 * 运行：node tests/e2e/metrics-dashboard.e2e.mjs（LSMOM_E2E_BASE 指定地址；只用于测试环境）
 */
import { open, check, BASE } from './lib.mjs';

const { browser, page, api, errors } = await open({ width: 1600, height: 1000 });
const shot = process.env.LSMOM_E2E_SHOT;
try {
  const me = (await api('GET', '/api/flow/me')).data;
  const list = await api('GET', '/api/metrics');
  check(
    list.code === 200 && list.data.some((m) => m.code === 'ls.core.issueCount') && list.data.some((m) => m.code === 'ls.core.workOnTimeRate'),
    '内置指标已发布：' + list.data.map((m) => m.code).join(', ')
  );
  const qs = await api('GET', '/api/q');
  check(
    qs.data.some((q) => q.code === 'ls.core.openIssuesByOrg'),
    '命名 Query 已登记'
  );

  // 重复口径
  const base = (await api('GET', '/api/metrics/ls.core.issueCount')).data.definition;
  const dup = await api('POST', '/api/metrics/validate', { ...base, code: 'acme.e2e.issueTotal', name: '异常总数' });
  check(
    dup.data.some((i) => i.code === 'METRIC_DUPLICATE' && i.message.includes('ls.core.issueCount')),
    '重复口径提示复用 ls.core.issueCount'
  );
  const sql = await api('POST', '/api/metrics/validate', { ...base, code: 'acme.e2e.x', sql: 'select 1' });
  check(sql.code !== 200, '指标定义不接受 SQL');

  // 命名 Query 参数越界
  const bad = await api('POST', '/api/q/ls.core.openIssuesByOrg', { severity: 'URGENT' });
  check(bad.code !== 200 && /severity/.test(bad.msg), '参数越界被拒：' + bad.msg);
  const okq = await api('POST', '/api/q/ls.core.openIssuesByOrg', {});
  check(okq.code === 200, `命名 Query 执行成功（${okq.data.rows.length} 行）`);

  // 本周登记 3 条异常
  const title = 'E2E看板' + Date.now().toString(36);
  for (let i = 0; i < 3; i++) {
    // 高严重度提交需要证据，这里用低严重度
    const r = await api('POST', '/api/op/ls.core.Issue.raise', {
      input: { type: 'DEFECT', title: title + i, severity: 'LOW', orgUnitId: me.orgUnitId }
    });
    const a = await api('GET', `/api/op/allowed?concept=ls.core.Issue&id=${r.data.targetId}`);
    const s = await api('POST', '/api/op/ls.core.Issue.submit', { input: {}, targetId: r.data.targetId, version: a.data.version });
    check(s.code === 200, `提交异常 ${i + 1}`);
  }

  // 看板：含 SQL 发布被拒
  const code = 'acme.e2e' + Date.now().toString(36) + '.qualityBoard';
  const sqlBoard = await api('POST', '/api/form/validate', {
    viewType: 'DASHBOARD',
    formCode: code,
    version: 1,
    widgets: [{ type: 'metricCard', title: '异常', metric: 'ls.core.issueCount', description: 'select count(*) from core_issue' }]
  });
  check(
    sqlBoard.data.issues.some((i) => i.code === 'DASHBOARD_SQL_FORBIDDEN'),
    '看板含 SQL 被拒'
  );
  const ghost = await api('POST', '/api/form/validate', {
    viewType: 'DASHBOARD',
    formCode: code,
    version: 1,
    widgets: [{ type: 'metricCard', title: '异常', metric: 'acme.none.metric' }]
  });
  check(
    ghost.data.issues.some((i) => i.code === 'DASHBOARD_METRIC_NOT_FOUND'),
    '绑定未发布指标被拒'
  );

  const board = {
    viewType: 'DASHBOARD',
    formCode: code,
    version: 1,
    title: '质量看板（E2E）',
    widgets: [
      { type: 'metricCard', title: '本周异常数', metric: 'ls.core.issueCount', range: 'thisWeek', span: 6 },
      { type: 'bar', title: '本月异常按严重度', metric: 'ls.core.issueCount', dimensions: ['severity'], range: 'thisMonth', span: 12 },
      { type: 'trend', title: '按期完成率', metric: 'ls.core.workOnTimeRate', grain: 'WEEK', range: 'last30Days', span: 6 },
      { type: 'table', title: '各组织未关闭异常', query: 'ls.core.openIssuesByOrg', span: 24 }
    ]
  };
  const d = await api('POST', '/api/form/drafts', { schema: board, note: 'E2E 看板' });
  check(d.code === 200 && d.data.issues.length === 0, '保存看板草稿');
  const p = await api('POST', `/api/form/views/${encodeURIComponent(code)}/1/publish`);
  check(p.code === 200, '发布看板：' + (p.data?.status ?? p.msg));

  // 界面
  await page.goto(`${BASE}/lsmom/dashboard/${encodeURIComponent(code)}`);
  await page.locator('[data-testid="widget-value-0"]').waitFor({ timeout: 30000 });
  await page.waitForTimeout(800);
  const card = Number((await page.locator('[data-testid="widget-value-0"]').textContent()).trim().split(/\s/)[0]);
  const expected = (
    await api(
      'POST',
      '/api/metrics/ls.core.issueCount/query',
      (() => {
        const t = new Date();
        const m = new Date(t.getFullYear(), t.getMonth(), t.getDate() - ((t.getDay() + 6) % 7));
        const f = (x) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`;
        return { from: f(m), to: f(new Date(m.getFullYear(), m.getMonth(), m.getDate() + 7)) };
      })()
    )
  ).data.rows[0].value;
  check(card === Number(expected) && card >= 3, `指标卡「本周异常数」= ${card}（接口 ${expected}）`);
  check((await page.locator('[data-testid="widget-0"]').textContent()).includes('ls.core.issueCount · v'), '指标卡显示指标编码与口径版本');
  check((await page.locator('[data-testid="widget-chart-1"] canvas').count()) === 1, '柱状图渲染');
  check((await page.locator('[data-testid="widget-table-3"]').textContent()).length > 0, '命名 Query 表格渲染');

  await page.locator('[data-testid="widget-value-0"]').click();
  await page.locator('[data-testid="drill-total"]').waitFor({ timeout: 10000 });
  await page.waitForTimeout(600);
  const total = Number(await page.locator('[data-testid="drill-total"]').textContent());
  check(total === card, `钻取明细 ${total} 条 = 指标值 ${card}`);
  const rows = await page.locator('[data-testid="drill-table"] .el-table__body tr').count();
  check(rows === Math.min(total, 50), `明细列表 ${rows} 行`);
  check((await page.locator('[data-testid="drill-table"]').textContent()).includes(title), '明细包含本次登记的异常');
  if (shot) await page.screenshot({ path: shot, fullPage: true });
  check(errors.length === 0, '页面无脚本错误' + (errors.length ? '：' + errors.join('; ') : ''));
} finally {
  await browser.close();
}
