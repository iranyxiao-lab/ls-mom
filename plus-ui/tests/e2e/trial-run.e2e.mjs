/**
 * modernize-ui-ux 8.6 端到端（无头 Chromium，部署环境）：设计器沙箱试运行。
 *   准备：用借还模板生成台账应用（ChangeSet → 演练 → 批准 → 发布）；
 *   表单设计器"试运行"：用当前草稿填写借还单 → 部门审核未填审核意见时试运行失败并指出原因 →
 *   填写后以模拟角色走完 提交 → 部门审核（车间主任）→ 确认归档（设备员），流程通过 →
 *   核对正式库：业务表、流程实例、待办、事件、审计、表单版本都没有试运行记录 → 一键清除试运行轨迹。
 * 运行：node tests/e2e/trial-run.e2e.mjs（只用于测试环境）
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
const run = Date.now().toString(36);
const ns = 'cust.trial' + run;
const FORM = ns + '.loanOrderForm';
const ORDER = ns + '.LoanOrder';

const ok = async (p, what) => {
  const r = await p;
  if (r.code !== 200) throw new Error(`${what}失败：${r.msg}`);
  return r.data;
};
const sql = (q) => {
  const r = spawnSync('docker', ['exec', `${PROJECT}-postgres-1`, 'sh', '-c', `psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "${q}"`], {
    encoding: 'utf8'
  });
  if (r.status !== 0) throw new Error('查询失败：' + r.stderr);
  return r.stdout.trim();
};

const s = await open({ width: 1600, height: 1000 });
try {
  const { page, api } = s;
  // —— 准备台账 ——
  const frag = await ok(api('GET', '/api/pack-templates/loan'), '读模板');
  const body = { fragment: frag, ns, title: `试运行 ${run}`, owner: '设备科王工', requester: '一车间周主任', initiatorRoles: ['team_leader'] };
  await ok(api('POST', '/api/pack-templates/grants', body), '授权');
  const cs = await ok(api('POST', '/api/pack-templates/draft', body), '生成草稿');
  const d = spawnSync(path.join(root, 'deploy/scripts/upgrade-drill.sh'), ['--changeset', String(cs.id), '--backup-from', PROJECT], {
    env: { ...process.env, LSMOM_E2E_BASE: BASE },
    encoding: 'utf8',
    timeout: 20 * 60 * 1000
  });
  if (d.status !== 0) console.log(((d.stdout ?? '') + (d.stderr ?? '')).slice(-3000));
  check(d.status === 0, `准备台账：演练通过（ChangeSet #${cs.id}）`);
  await ok(api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '试运行用例' }), '批准');
  await ok(api('POST', `/api/changesets/${cs.id}/release`), '发布');
  const table = sql(`select table_name from meta_concept where code = '${ORDER}'`);
  const counts = () =>
    sql(`select (select count(*) from ${table}) || ',' || (select count(*) from biz_flow_instance where biz_type = '${ORDER}') || ',' ||
      (select count(*) from biz_task where biz_type = '${ORDER}') || ',' || (select count(*) from core_domain_event where subject_concept = '${ORDER}') || ',' ||
      (select count(*) from op_audit where target_concept = '${ORDER}') || ',' || (select count(*) from meta_view where code = '${FORM}')`);

  // —— 设计器：试运行 ——
  await page.goto(`${BASE}/lsmom/form-designer/${encodeURIComponent(FORM)}`);
  await page.getByTestId('designer').waitFor({ timeout: 30000 });
  await page.waitForTimeout(800);
  // 打开设计器会派生工作草稿（设计器本身的行为），之后再取基线
  const before = counts();
  await page.locator('#tab-trial').click();
  const trial = page.getByTestId('trial');
  await trial.getByTestId('trial-flow').waitFor({ timeout: 15000 });
  check(/部门审核 → 确认归档/.test((await trial.getByTestId('trial-flow').textContent()) ?? ''), '显示绑定的审批流与节点');
  check(((await trial.getByTestId('trial-initiator').textContent()) ?? '').includes('吴班长'), '默认发起人为有提交权限的班组长');

  const pick = async (testId, text, typed) => {
    const sel = trial.getByTestId(testId);
    await sel.click();
    if (typed) await sel.locator('input').first().fill(typed);
    await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
    await page.keyboard.press('Escape').catch(() => undefined);
  };
  const form = trial.getByTestId('trial-form');
  await form.getByTestId('input-loanNo').fill(`TRIAL-${run}`);
  await pick('input-borrower', '郑师傅', '郑');
  await form.getByTestId('field-loanDate').locator('input').fill('2026-11-11');
  await form.getByTestId('field-loanDate').locator('input').press('Enter');
  await page.keyboard.press('Escape');
  await pick('input-purpose', '检修');
  await form.getByTestId('add-row-loanLine').click();
  const line = form.getByTestId('subtable-loanLine');
  await line.getByTestId('input-item').first().fill('扭力扳手');
  await line.getByTestId('input-qty').first().locator('input').fill('2');

  // 部门审核要求填写审核意见：不填时试运行失败并指出原因
  await trial.getByTestId('trial-run').click();
  const result = trial.getByTestId('trial-result');
  await result.waitFor({ timeout: 60000 });
  const failed = (await result.textContent()) ?? '';
  check(failed.includes('试运行失败') && failed.includes('部门审核'), '未填审核意见：试运行失败并指出节点 — ' + failed.trim());
  check((await trial.locator('[data-kind="task"] .ls-trial__err').count()) === 1, '轨迹中失败步骤标红并显示原因');

  // 填写审核意见后走完 提交 → 审核 → 确认
  await trial.getByTestId('trial-input-deptAudit-auditNote').fill('同意借用（试运行）');
  await trial.getByTestId('trial-run').click();
  await page.waitForFunction(() => document.querySelector('[data-testid="trial-result"]')?.textContent?.includes('试运行通过'), null, {
    timeout: 60000
  });
  const passed = (await result.textContent()) ?? '';
  check(passed.includes('提交 → 部门审核（通过） → 确认归档（通过）') && passed.includes('APPROVED'), '走完提交→审核→确认，流程通过：' + passed.trim());
  const steps = await trial.locator('[data-testid^="trial-step-"]').allTextContents();
  check(steps[0].includes('使用当前草稿'), '试运行使用设计器当前草稿');
  check(steps.some((t) => t.includes('部门审核') && t.includes('模拟·周主任') && t.includes('车间主任')), '部门审核以模拟车间主任身份办理');
  check(steps.some((t) => t.includes('确认归档') && t.includes('设备员')), '确认归档以模拟设备员身份办理');
  check(steps.some((t) => t.includes('可编辑：auditNote')), '轨迹显示节点可编辑字段（按草稿字段策略）');
  check(steps.some((t) => t.includes('approved') && t.includes('DONE')), '通过回调在沙箱内执行');

  // —— 正式库没有任何试运行记录 ——
  const after = counts();
  check(after === before, `正式库无试运行记录（业务表,流程实例,待办,事件,审计,表单版本：${before} → ${after}）`);
  check(sql(`select count(*) from ${table} where loan_no like 'TRIAL-%'`) === '0', '业务表没有试运行单据');

  // —— 一键清除 ——
  const hist = await ok(api('GET', `/api/flow/trial?formCode=${encodeURIComponent(FORM)}`), '读试运行记录');
  check(hist.length === 2, '保留 2 次试运行轨迹');
  await trial.getByTestId('trial-clear').click();
  await page.locator('.el-message-box').getByRole('button', { name: /确定|OK/ }).click();
  await page.locator('.el-message--success', { hasText: '已清除 2 次试运行数据' }).first().waitFor({ timeout: 10000 });
  check(sql(`select count(*) from flow_trial_run where form_code = '${FORM}'`) === '0', '一键清除后试运行数据为 0');
  check(counts() === before, '清除后正式库仍无变化');
  check(s.errors.length === 0, '页面无脚本错误' + (s.errors.length ? '：' + s.errors.join(' | ') : ''));
} finally {
  await s.browser.close();
}
