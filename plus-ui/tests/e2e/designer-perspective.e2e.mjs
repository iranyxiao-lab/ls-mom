/**
 * modernize-ui-ux 8.4 端到端（无头 Chromium，部署环境）：所见即所得画布与视角切换。
 *   准备：用借还模板生成一个审批节点为"车间审核"的台账应用（ChangeSet → 演练 → 批准 → 发布）；
 *   表单设计器打开实时预览：桌面 / 移动 / 只读详情 / 打印 / 流程节点视角切换；
 *   属性修改（勾选只读）后 300ms 内预览更新（页面内计时）；
 *   切到"车间审核"节点视角：只读字段显示为只读样式（文字底色、无输入框），审核意见可编辑且必填。
 * 运行：node tests/e2e/designer-perspective.e2e.mjs（只用于测试环境）
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
const run = Date.now().toString(36);
const ns = 'cust.persp' + run;
const FORM = ns + '.loanOrderForm';

const ok = async (p, what) => {
  const r = await p;
  if (r.code !== 200) throw new Error(`${what}失败：${r.msg}`);
  return r.data;
};

const s = await open({ width: 1800, height: 1000 });
try {
  const { page, api } = s;
  // —— 准备：节点为"车间审核"的借还台账 ——
  const frag = await ok(api('GET', '/api/pack-templates/loan'), '读模板');
  frag.flow.nodes[0] = { ...frag.flow.nodes[0], key: 'workshopAudit', name: '车间审核' };
  const body = { fragment: frag, ns, title: `视角预览 ${run}`, owner: '设备科王工', requester: '一车间周主任', initiatorRoles: ['team_leader'] };
  await ok(api('POST', '/api/pack-templates/grants', body), '授权');
  const cs = await ok(api('POST', '/api/pack-templates/draft', body), '生成草稿');
  const d = spawnSync(path.join(root, 'deploy/scripts/upgrade-drill.sh'), ['--changeset', String(cs.id), '--backup-from', PROJECT], {
    env: { ...process.env, LSMOM_E2E_BASE: BASE },
    encoding: 'utf8',
    timeout: 20 * 60 * 1000
  });
  if (d.status !== 0) console.log(((d.stdout ?? '') + (d.stderr ?? '')).slice(-3000));
  check(d.status === 0, `准备台账：演练通过（ChangeSet #${cs.id}）`);
  await ok(api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: '视角预览用例' }), '批准');
  await ok(api('POST', `/api/changesets/${cs.id}/release`), '发布');

  // —— 设计器：实时预览 ——
  await page.goto(`${BASE}/lsmom/form-designer/${encodeURIComponent(FORM)}`);
  const canvas = page.locator('[data-testid="designer"] ._fd-drag-box').first();
  await canvas.waitFor({ timeout: 30000 });
  await page.waitForTimeout(800);
  await page.getByTestId('live-toggle').click();
  const live = page.getByTestId('live-canvas');
  await live.locator('[data-field-key="loanNo"]').waitFor();
  check((await live.locator('[data-field-key="loanNo"]').getAttribute('data-access')) === 'required', '桌面视角：借用单号可编辑且必填');

  // 视角切换：移动 / 只读详情 / 打印
  const persp = page.getByTestId('live-preview').getByTestId('preview-device');
  for (const [label, sel] of [
    ['移动', '.schema-mobile, [data-testid="mobile-form"], .van-cell-group, form'],
    ['只读详情', '.schema-detail, [data-testid="schema-detail"], .el-descriptions'],
    ['打印', '.schema-print, [data-testid="schema-print"], .print-page, table']
  ]) {
    await persp.locator('.el-radio-button', { hasText: label }).click();
    await live.locator(sel).first().waitFor({ timeout: 5000 });
    check(true, `切换到${label}视角`);
  }
  await persp.locator('.el-radio-button', { hasText: '桌面' }).click();
  await live.locator('[data-field-key="loanNo"]').waitFor();

  // 属性修改后 300ms 内更新预览：选中借用单号 → 勾选只读（页面内从点击到预览变化计时）
  await canvas.locator('._fd-drag-item', { hasText: '借用单号' }).first().click();
  await page.getByTestId('field-panel').waitFor();
  await page.evaluate(() => {
    const w = window;
    w.__lat = {};
    const root = document.querySelector('[data-testid="live-canvas"]');
    new MutationObserver(() => {
      const el = root.querySelector('[data-field-key="loanNo"]');
      if (w.__lat.t0 && !w.__lat.t1 && el?.getAttribute('data-access') === 'readonly') w.__lat.t1 = performance.now();
    }).observe(root, { subtree: true, childList: true, attributes: true });
    document.addEventListener(
      'click',
      (e) => {
        if (e.target.closest?.('[data-testid="ext-readonly"]') && !w.__lat.t0) w.__lat.t0 = performance.now();
      },
      true
    );
  });
  await page.getByTestId('ext-readonly').click();
  await page.waitForFunction(() => window.__lat.t1, null, { timeout: 3000 });
  const ms = await page.evaluate(() => Math.round(window.__lat.t1 - window.__lat.t0));
  check(ms <= 300, `勾选只读后 ${ms}ms 预览更新（≤300ms）`);
  await page.getByTestId('ext-readonly').click();
  await page.waitForFunction(
    () => document.querySelector('[data-testid="live-canvas"] [data-field-key="loanNo"]')?.getAttribute('data-access') !== 'readonly'
  );

  // 车间审核节点视角
  await persp.locator('.el-radio-button', { hasText: '流程节点' }).click();
  const nodeSel = page.getByTestId('live-preview').getByTestId('persp-node-select');
  await nodeSel.waitFor();
  const nodeText = await nodeSel.textContent();
  check(nodeText?.includes('车间审核'), '节点视角显示节点名"车间审核"：' + nodeText);
  await live.locator('[data-field-key="auditNote"]').waitFor();
  const access = async (k) => live.locator(`[data-field-key="${k}"]`).getAttribute('data-access');
  check(
    (await access('loanNo')) === 'readonly' && (await access('purpose')) === 'readonly' && (await access('returnDue')) === 'readonly',
    '车间审核视角：其余字段只读'
  );
  check((await access('auditNote')) === 'required', '车间审核视角：审核意见可编辑且必填');
  const ro = live.locator('[data-field-key="loanNo"]');
  check((await ro.locator('input, textarea').count()) === 0 && (await ro.locator('.ls-field__text').count()) === 1, '只读字段显示为文字，无输入框');
  const bg = await ro.locator('.ls-field__text').evaluate((el) => getComputedStyle(el).backgroundColor);
  check(bg !== 'rgba(0, 0, 0, 0)' && bg !== 'transparent', '只读字段带只读底色：' + bg);
  check((await live.locator('[data-field-key="auditNote"]').locator('input, textarea').count()) >= 1, '审核意见为输入框');

  // 节点策略面板显示节点名；预览页与设计页视角一致
  await page.locator('.el-tabs__item', { hasText: /^节点策略$/ }).click();
  check(
    (await page.getByTestId('policy-workshopAudit').textContent())?.includes('车间审核（workshopAudit）'),
    '节点策略显示"车间审核（workshopAudit）"'
  );
  await page.locator('#tab-preview').click();
  await page.getByTestId('preview').locator('[data-field-key="auditNote"]').waitFor();
  check((await page.getByTestId('preview').locator('[data-field-key="loanNo"]').getAttribute('data-access')) === 'readonly', '预览页沿用同一视角');
  check(s.errors.length === 0, '页面无脚本错误' + (s.errors.length ? '：' + s.errors.join(' | ') : ''));
} finally {
  await s.browser.close();
}
