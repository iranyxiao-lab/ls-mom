/**
 * modernize-ui-ux 6.7 端到端（无头 Chromium，部署环境）：任务中心。
 *   在课程台账导入 5000 行（其中 2 行及格分不是数字）→ 后台检查，对话框显示进度；
 *   关掉对话框、切到首页 → 检查完成后收到通知（共 5000 行：可导入 4998 行，错误 2 行）；
 *   点通知打开任务中心，看到错误行报告，有错误时不能确认导入；
 *   改好后重新导入 5000 行 → 确认导入 → 切到首页 → 收到"已导入 5000 条"通知，台账里能查到 5000 条。
 * 运行：node tests/e2e/task-center.e2e.mjs（LSMOM_E2E_BASE 指定环境）
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-task-'));
const COURSE = 'ls.training.Course';
const run = Date.now().toString(36).toUpperCase();
const N = 5000;
const BAD = new Set([777, 4321]);

const s = await open({ user: 'admin' });
const p = s.page;

const xlsx = (name, rows) => {
  const f = path.join(work, name);
  const j = path.join(work, name + '.json');
  fs.writeFileSync(j, JSON.stringify(rows));
  execFileSync('python3', [path.join(here, 'make-xlsx.py'), f, '@' + j]);
  return f;
};
const head = ['课程编码*', '课程名称*', '适用岗位', '及格分*', '有效期（月）*', '来源', '所属组织ID'];
const goHome = async () => {
  await p.locator('.tags-view-item', { hasText: '首页' }).first().click();
  await p.waitForURL(/\/index/, { timeout: 15000 });
};
const notice = (text) => p.locator('.ls-task-notice', { hasText: text }).last();

try {
  const orgs = (await s.api('GET', '/api/asset/orgs')).data;
  const org = orgs.find((o) => o.unit_type === 'WORKSHOP') ?? orgs[0];
  const rows = (bad) => {
    const out = [head];
    for (let i = 1; i <= N; i++)
      out.push([`TC-${run}-${bad ? 'B' : 'G'}${i}`, `批量课程 ${i}`, '操作工', bad && BAD.has(i) ? '六十' : '60', '12', 'MANUAL', String(org.id)]);
    return out;
  };

  await p.goto(BASE + '/lsmom/ledger/' + COURSE);
  await p.getByTestId('import').click({ timeout: 30000 });

  // 第一轮：带 2 行错误，检查中关掉对话框并切到首页
  const t0 = Date.now();
  await p.getByTestId('import-file').setInputFiles(xlsx('bad.xlsx', rows(true)));
  await p.getByTestId('import-progress').waitFor({ timeout: 15000 });
  check(/可以离开本页/.test(await p.getByTestId('import-progress').textContent()), '对话框显示进度并提示可以离开');
  await p.getByTestId('ledger-import').getByRole('button', { name: '取消' }).click();
  await goHome();
  const running = await p
    .getByTestId('tasks-running')
    .locator('.el-badge__content')
    .textContent()
    .catch(() => '');
  console.log(`  · 切到首页时进行中任务角标：${running || '（已完成）'}`);
  const n1 = notice('导入检查');
  await n1.waitFor({ timeout: 120000 });
  const n1Text = await n1.textContent();
  check(n1Text.includes('已完成') && n1Text.includes(`共 ${N} 行：可导入 ${N - 2} 行，错误 2 行`), '离开页面后收到检查完成通知：' + n1Text);
  check(p.url().includes('/index'), '通知出现在首页（已离开台账）');
  console.log(`  · 5000 行检查耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`);

  // 点通知 → 任务中心：错误行报告，不能确认导入
  await n1.click();
  const drawer = p.getByTestId('task-drawer');
  await drawer.waitFor({ timeout: 15000 });
  const item = drawer.locator('[data-kind="IMPORT_CHECK"][data-status="DONE"]').first();
  const errs = await item.getByTestId('task-errors').textContent();
  check(
    errs.includes(String(778)) && errs.includes(String(4322)) && errs.includes('passScore'),
    '任务中心列出错误行（行号与字段）：' + errs.slice(0, 200)
  );
  check((await item.getByTestId('task-confirm-import').count()) === 0, '有错误行时不能确认导入');
  check((await item.textContent()).includes('修改文件后重新导入'), '提示修改文件后重新导入');
  await p.keyboard.press('Escape');
  await drawer.waitFor({ state: 'hidden', timeout: 10000 });

  // 第二轮：全部正确，在对话框里等检查完成 → 确认导入 → 切到首页
  await p.goto(BASE + '/lsmom/ledger/' + COURSE);
  await p.getByTestId('import').click({ timeout: 30000 });
  await p.getByTestId('import-file').setInputFiles(xlsx('good.xlsx', rows(false)));
  await p
    .getByTestId('import-summary')
    .filter({ hasText: `可导入 ${N} 行` })
    .waitFor({ timeout: 120000 });
  check((await p.locator('.ls-task-notice', { hasText: '导入检查' }).count()) <= 1, '留在页面时检查完成不重复弹通知');
  await p.getByTestId('import-confirm').click();
  await p.getByTestId('import-progress').waitFor({ timeout: 15000 });
  await p.getByTestId('ledger-import').getByRole('button', { name: '取消' }).click();
  await goHome();
  const n2 = notice('导入：课程');
  await n2.waitFor({ timeout: 300000 });
  const n2Text = await n2.textContent();
  check(n2Text.includes(`已导入 ${N} 条`), '离开页面后收到导入完成通知：' + n2Text);
  const q = await s.api('POST', `/api/crud/${COURSE}/query`, {
    filters: [{ field: 'courseCode', op: 'like', value: `TC-${run}-G` }],
    page: 1,
    size: 1
  });
  check(q.data.total === N, `台账里能查到 ${N} 条（实际 ${q.data.total}）`);

  // 刷新后通知不再重复
  await p.reload();
  await p.waitForTimeout(3000);
  check((await p.locator('.ls-task-notice').count()) === 0, '已通知的任务刷新后不再弹');
  await p.getByTestId('tasks-open').click();
  await drawer.waitFor({ timeout: 15000 });
  check((await drawer.locator('[data-kind="IMPORT"][data-status="DONE"]').first().textContent()).includes(`已导入 ${N} 条`), '任务中心保留导入结果');
  await p.keyboard.press('Escape');
  await drawer.waitFor({ state: 'hidden', timeout: 10000 });

  // 导出：发起后离开页面，完成通知 → 任务中心下载
  await p.goto(BASE + '/lsmom/ledger/' + COURSE);
  const kwBox = p.locator('input[data-testid="keyword"], [data-testid="keyword"] input').first();
  await kwBox.fill(`TC-${run}-G`, { timeout: 30000 });
  await kwBox.press('Enter');
  await p.waitForTimeout(1500);
  await p.getByTestId('export').click();
  await goHome();
  const n3 = notice('导出：课程');
  await n3.waitFor({ timeout: 120000 });
  check((await n3.textContent()).includes('可下载'), '离开页面后收到导出完成通知');
  await n3.click();
  await drawer.waitFor({ timeout: 15000 });
  const [dl] = await Promise.all([
    p.waitForEvent('download'),
    drawer.locator('[data-kind="EXPORT"][data-status="DONE"]').first().getByTestId('task-download').click()
  ]);
  const out = path.join(work, 'export.xlsx');
  await dl.saveAs(out);
  check(fs.readFileSync(out).subarray(0, 2).toString() === 'PK', '任务中心下载导出文件');
  check(s.errors.length === 0, '页面无脚本错误：' + s.errors.join('；'));
} finally {
  await s.browser.close();
  fs.rmSync(work, { recursive: true, force: true });
}
