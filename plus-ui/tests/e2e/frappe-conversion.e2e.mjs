/**
 * 17.4 部署环境实测（无头）：Frappe 静态转换的完整闭环。
 * ls-mom-cli frappe-import（ToDo DocType＋自编 Workflow / Report / Fixture / Patch / Server Script）→ generate →
 * 应用中心上传 ChangeSet → 回写测试阶段并批准 → 发布被门禁拦截（禁止自动转换项未处理）→
 * 逐项处理（resolve）并记录人工修改（record-edit）→ 新版本发布成功 → 应用中心「来源」页签与来源接口可见 Frappe 来源、许可证、转换器、人工修改历史。
 * 前置：ls-mom-cli/target/ls-mom-cli-exec.jar（mvn -pl ls-mom-cli package）。只用于测试环境。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { open, check, BASE } from './lib.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const jar = path.join(root, 'ls-mom-cli/target/ls-mom-cli-exec.jar');
const app = path.join(root, 'ls-mom-cli/src/test/resources/frappe/todo-app');
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-frappe-e2e-'));
const ns = 'acme.td' + Date.now().toString(36);
const concept = ns + '.ToDo';
const cli = (...args) => execFileSync('java', ['-jar', jar, ...args], { encoding: 'utf8' });
const irFile = path.join(work, 'todo.ir.json');

check(fs.existsSync(jar), 'CLI 已构建：' + path.relative(root, jar));
const out = cli('frappe-import', app, '--namespace', ns, '--app', 'frappe', '--version', 'develop@5511749c', '--out', irFile);
check(out.includes('禁止自动转换 3'), '静态转换：' + out.trim());
const ir = JSON.parse(fs.readFileSync(irFile, 'utf8'));
check(ir.provenance.license === 'MIT' && ir.provenance.sourceType === 'FRAPPE', '识别 MIT 许可证，来源类型 FRAPPE');
cli('generate', irFile, '--out', path.join(work, 'v1'));

const { browser, page, api, errors } = await open({ width: 1700, height: 1000 });
const tid = (id) => page.locator(`[data-testid="${id}"]`);
async function gotoApps(tab) {
  await page.goto(`${BASE}/lsmom/apps`);
  await tid('app-center').waitFor({ timeout: 30000 });
  await page.waitForTimeout(600);
  if (tab) {
    await page.locator('.el-tabs__item', { hasText: tab }).first().click();
    await page.waitForTimeout(600);
  }
}
async function upload(zip) {
  await gotoApps('变更发布');
  await page.locator('[data-testid="upload-pack"] input[type="file"]').setInputFiles(zip);
  const ok = page.locator('.el-message--success', { hasText: '已提交 ChangeSet' }).last();
  await ok.waitFor({ timeout: 30000 });
  const id = Number(/#(\d+)/.exec(await ok.textContent())[1]);
  const cs = (await api('GET', `/api/changesets/${id}`)).data;
  // 测试阶段：转换生成物已在 CLI 测试中跑过临时库安装与场景，这里按演练脚本接口回写
  for (const st of ['SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE']) {
    await api('POST', `/api/changesets/${id}/stages`, {
      stage: st,
      status: 'PASSED',
      artifactSha: cs.artifactSha,
      detail: { note: 'frappe-import 样例' }
    });
  }
  check((await api('POST', `/api/changesets/${id}/approve`, { approve: true, note: '同意' })).code === 200, `ChangeSet #${id} 测试通过并批准`);
  await gotoApps('变更发布');
  return id;
}

try {
  // 1. 含 Server Script 的转换结果：已批准，但未处理需人工项 → 发布被拦截
  const id1 = await upload(path.join(work, 'v1/changeset', `${ns}-0.1.0.zip`));
  const gate = (await api('GET', `/api/changesets/${id1}/gate`)).data;
  check(
    !gate.ready && gate.problems.some((p) => p.includes('禁止自动转换项未处理：server_script/auto_close.json')),
    '门禁列出未处理的 Server Script'
  );
  await tid(`btn-release-${id1}`).click();
  const err = page.locator('.el-message--error, .el-notification').filter({ hasText: '禁止自动转换项未处理' }).first();
  await err.waitFor({ timeout: 10000 });
  check(true, '界面发布被拒：' + (await err.textContent()).trim().slice(0, 60) + '…');
  check((await api('GET', `/api/packs/${encodeURIComponent(ns)}`)).code !== 200, '未安装');

  // 2. 人工处理：记录修改、逐项关闭，升版本重新生成
  ir.pack.version = '0.1.1';
  fs.writeFileSync(irFile, JSON.stringify(ir, null, 2));
  cli('record-edit', irFile, '--by', '实施顾问', '--path', 'concepts/to-do.yaml', '--reason', 'referenceType 改为文本说明', '--lines', '2');
  cli('resolve', irFile, '--path', 'server_script/auto_close.json', '--by', '实施顾问', '--resolution', '改用平台定时提醒，不迁移脚本');
  for (const m of JSON.parse(fs.readFileSync(irFile, 'utf8')).provenance.manualRequired) {
    if (m.status !== 'RESOLVED') cli('resolve', irFile, '--path', m.path, '--by', '实施顾问', '--resolution', '已人工评审');
  }
  const gen = cli('generate', irFile, '--out', path.join(work, 'v2'));
  check(!gen.includes('需人工处理'), '处理后重新生成，无未关闭项');
  const id2 = await upload(path.join(work, 'v2/changeset', `${ns}-0.1.1.zip`));
  check((await api('GET', `/api/changesets/${id2}/gate`)).data.ready, '门禁通过');
  await tid(`btn-release-${id2}`).click();
  await page.locator('.el-message--success', { hasText: `已发布 ${ns} 0.1.1` }).waitFor({ timeout: 120000 });
  check(true, `真人发布成功：${ns} 0.1.1`);

  // 3. 转换得到的台账可用：种子数据、工作流
  const rows = (await api('POST', `/api/crud/${encodeURIComponent(concept)}/query`, {})).data;
  check((rows.rows ?? rows.records ?? rows).length >= 2, 'Fixture 种子已导入');

  // 4. AI-03：来源接口与应用中心「来源」页签
  const p = (await api('GET', `/api/packs/${encodeURIComponent(ns)}/provenance?concept=${encodeURIComponent(concept)}`)).data;
  check(p.sourceType === 'FRAPPE' && p.license === 'MIT' && p.converter.name === 'ls-mom-cli frappe-import', '来源接口：Frappe、MIT、转换器');
  check(p.concept.source.name === 'ToDo' && p.concept.fields.allocatedTo.field === 'allocated_to', '对象与字段来源：DocType ToDo / allocated_to');
  check(p.manualEdits[0].by === '实施顾问' && p.manualEdits[0].reason === 'referenceType 改为文本说明', '人工修改历史');
  await gotoApps();
  await page.locator('[data-testid="table-packs"] .el-table__body tr').filter({ hasText: ns }).first().click();
  await tid('pack-drawer').waitFor({ timeout: 10000 });
  await page.locator('.el-drawer .el-tabs__item', { hasText: '来源' }).click();
  await page.waitForTimeout(500);
  const info = await tid('prov-info').textContent();
  check(
    info.includes('Frappe 转换') && info.includes('MIT') && info.includes('ls-mom-cli frappe-import 1.0.0'),
    '来源页签：来源类型、许可证、转换器版本'
  );
  check((await tid('prov-elements').textContent()).includes('DocType · ToDo · desk/doctype/todo/todo.json'), '来源页签：对象来源');
  check((await tid('prov-edits').textContent()).includes('referenceType 改为文本说明'), '来源页签：人工修改历史');
  check((await tid('prov-manual').textContent()).includes('改用平台定时提醒，不迁移脚本'), '来源页签：需人工项及处理结论');
  check((await tid('prov-open').count()) === 0, '无未处理项提示');
  if (process.env.LSMOM_E2E_SHOT) await page.screenshot({ path: process.env.LSMOM_E2E_SHOT, fullPage: true });
  check(errors.length === 0, '页面无脚本错误' + (errors.length ? '：' + errors.join('; ') : ''));
  console.log('Frappe 转换闭环通过');
} finally {
  await browser.close();
}
