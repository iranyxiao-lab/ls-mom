/**
 * 11.4 / 12.x 部署环境实测（无头）：按 docs/pack-authoring.md 的示例客户 Pack 走完整生命周期。
 * 打包 → 协同应用中心上传（校验与依赖分析）→ 未测试发布被拒 → 升级演练脚本（备份恢复到临时环境、安装、测试、回写）→
 * 未批准发布被拒 → 批准 → 发布 → 应用中心核对与 meta_pack 一致 → 使用量 → 手工改表单标签 → 漂移报告并回灌为 ChangeSet →
 * 停用后接口不可用、数据可导出 → 超期未用被标记建议下线并通知负责人 → 迁移失败的升级被演练与门禁拦截。
 * 运行：node tests/e2e/pack-lifecycle.e2e.mjs（LSMOM_E2E_BASE 指定地址，LSMOM_E2E_PROJECT 指定 Compose 项目；只用于测试环境）
 */
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { open, check, BASE } from './lib.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
const psql = (sql) =>
  execFileSync('docker', ['compose', '-p', PROJECT, 'exec', '-T', 'postgres', 'psql', '-U', 'lsmom', '-d', 'lsmom', '-Atc', sql], {
    cwd: path.join(root, 'deploy'),
    encoding: 'utf8'
  }).trim();

const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-pack-e2e-'));
const suffix = Date.now().toString(36);
const ns = 'acme.vis' + suffix;
const concept = ns + '.Visit';
const table = 'c_' + ns.replace(/\./g, '_') + '_visit';

/** 复制示例 Pack，换成唯一命名空间与指定版本，返回 zip 路径。 */
function build(version, extra = {}, ownerParty, packNs = ns) {
  const tbl = 'c_' + packNs.replace(/\./g, '_') + '_visit';
  const dir = path.join(work, `${packNs}-${version}`);
  fs.cpSync(path.join(root, 'packs/examples/acme-visitor'), dir, { recursive: true });
  for (const f of fs.readdirSync(dir, { recursive: true })) {
    const p = path.join(dir, f);
    if (!fs.statSync(p).isFile()) continue;
    let s = fs
      .readFileSync(p, 'utf8')
      .replaceAll('acme.visitor', packNs)
      .replaceAll('c_acme_visitor_visit', tbl)
      .replaceAll('idx_acme_visitor_visit_host', `idx_${tbl}_host`);
    if (f === 'pack.yaml') {
      s = s
        .replace(/^version: .*$/m, `version: ${version}`)
        .replace('owner: { name: 行政部王经理 }', `owner: { name: 行政部王经理, party: ${ownerParty} }`);
    }
    fs.writeFileSync(p, s);
  }
  for (const [f, s] of Object.entries(extra)) fs.writeFileSync(path.join(dir, f), s);
  const zip = path.join(work, `${packNs}-${version}.zip`);
  execFileSync('python3', [path.join(root, 'scripts/pack-build.py'), dir, zip]);
  return zip;
}

function drill(id) {
  const r = spawnSync(path.join(root, 'deploy/scripts/upgrade-drill.sh'), ['--changeset', String(id), '--backup-from', PROJECT], {
    env: { ...process.env, LSMOM_E2E_BASE: BASE },
    encoding: 'utf8',
    timeout: 20 * 60 * 1000
  });
  return { code: r.status, out: (r.stdout ?? '') + (r.stderr ?? '') };
}

const { browser, page, api, errors } = await open({ width: 1700, height: 1000 });
const shot = process.env.LSMOM_E2E_SHOT;
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
const packRow = () => page.locator('[data-testid="table-packs"] .el-table__body tr').filter({ hasText: ns }).first();

try {
  const me = (await api('GET', '/api/flow/me')).data;
  const ownerParty = psql(`select code from core_party where id = ${me.partyId}`);
  check(ownerParty.length > 0, '负责人使用当前用户的人员编码 ' + ownerParty);

  // 1. 打包并在应用中心上传
  const zip = build('1.0.0', {}, ownerParty);
  check(fs.statSync(zip).size > 0, '按文档打包示例 Pack：' + path.basename(zip));
  await gotoApps('变更发布');
  check((await page.locator('.el-message--error, .el-notification').count()) === 0, '应用中心打开无报错');
  await page.locator('[data-testid="upload-pack"] input[type="file"]').setInputFiles(zip);
  const submitted = page.locator('.el-message--success', { hasText: '已提交 ChangeSet' });
  await submitted.waitFor({ timeout: 30000 });
  const id = Number(/#(\d+)/.exec(await submitted.textContent())[1]);
  check(id > 0, '上传后生成 ChangeSet #' + id);
  await page.waitForTimeout(800);
  check((await tid(`cs-status-${id}`).textContent()).includes('测试中'), 'ChangeSet 状态：测试中');
  check((await tid(`stage-${id}-VALIDATE`).getAttribute('class')).includes('ok'), '校验与依赖分析通过');
  check((await tid(`stage-${id}-SANDBOX`).getAttribute('class')).includes('none'), '沙箱安装尚未执行');

  // 2. 未测试就发布 → 门禁拒绝
  await tid(`btn-release-${id}`).click();
  await page.locator('.el-message--error, .el-notification').first().waitFor({ timeout: 10000 });
  const gateMsg = await page.locator('.el-message--error, .el-notification').first().textContent();
  check(gateMsg.includes('门禁') && gateMsg.includes('沙箱安装未执行'), '未测试的 ChangeSet 发布被门禁拒绝：' + gateMsg.trim().slice(0, 60));

  // 3. 升级演练：备份恢复到临时环境 → 安装 → 测试 → 回写
  const d1 = drill(id);
  if (d1.code !== 0) console.log(d1.out);
  check(d1.code === 0, '升级演练通过（退出码 ' + d1.code + '）');
  check(
    ['SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE'].every((s) => d1.out.includes('✓ ' + s)),
    '演练四个阶段全部通过'
  );
  check(d1.out.includes('门禁通过'), '演练后门禁检查通过');

  // 4. 未批准发布被拒 → 批准 → 发布
  await gotoApps('变更发布');
  check((await tid(`cs-status-${id}`).textContent()).includes('待审批'), '测试全部通过后进入待审批');
  for (const s of ['VALIDATE', 'SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE']) {
    if (!(await tid(`stage-${id}-${s}`).getAttribute('class')).includes('ok')) throw new Error(s + ' 未显示为通过');
  }
  check(true, '页面显示五个阶段均已通过');
  await page.locator('.el-message, .el-notification').evaluateAll((els) => els.forEach((e) => e.remove()));
  await tid(`btn-release-${id}`).click();
  await page.locator('.el-message--error, .el-notification').first().waitFor({ timeout: 10000 });
  check((await page.locator('.el-message--error, .el-notification').first().textContent()).includes('未经人工批准'), '测试通过但未批准的发布被拒');
  check(psql(`select count(*) from meta_pack where code = '${ns}'`) === '0', '被拒后未安装到当前环境');
  await tid(`btn-approve-${id}`).click();
  await page.locator('.el-message-box input').fill('演练通过，同意上线');
  await page.locator('.el-message-box .el-button--primary').click();
  await page.locator('.el-message--success', { hasText: '已批准' }).waitFor({ timeout: 10000 });
  await page.waitForTimeout(600);
  await tid(`btn-release-${id}`).click();
  await page.locator('.el-message--success', { hasText: `已发布 ${ns} 1.0.0` }).waitFor({ timeout: 120000 });
  await page.waitForTimeout(600);
  check((await tid(`cs-status-${id}`).textContent()).includes('已发布'), '批准后发布成功');

  // 5. 应用中心与 meta_pack 一致
  await gotoApps();
  const db = psql(
    `select version || '|' || status || '|' || owner || '|' || requester || '|' || review_due from meta_pack where code = '${ns}'`
  ).split('|');
  const row = packRow();
  const text = await row.textContent();
  check(db[0] === '1.0.0' && db[1] === 'ENABLED', 'meta_pack：' + db.join(' / '));
  check(
    text.includes('访客登记') &&
      text.includes('1.0.0') &&
      text.includes('启用') &&
      text.includes(db[2]) &&
      text.includes(db[3]) &&
      text.includes(db[4]),
    '应用中心显示的版本、状态、负责人、提出人、复审日期与 meta_pack 一致'
  );
  check(text.includes('客户'), '标记为客户 Pack');
  check(
    psql(`select count(*) from flyway_history_${ns.replace(/\./g, '_')} where success and script = 'V1__host_index.sql'`) === '1',
    '补充迁移记录在独立历史表 flyway_history_' + ns.replace(/\./g, '_')
  );

  // 6. 使用量：登记一位访客并签到
  const created = await api('POST', `/api/crud/${encodeURIComponent(concept)}`, {
    visitorName: 'E2E访客',
    hostName: '张工',
    visitDate: '2026-11-10'
  });
  check(created.code === 200, '经通用台账登记访客');
  const allowed = await api('GET', `/api/op/allowed?concept=${encodeURIComponent(concept)}&id=${created.data.id}`);
  const ci = await api('POST', `/api/op/${concept}.checkIn`, { input: {}, targetId: String(created.data.id), version: allowed.data.version });
  check(ci.code === 200, '执行 Pack 的签到 Operation');
  await gotoApps();
  check(Number(await packRow().locator('[data-testid="pack-uses"]').textContent()) >= 1, '近 90 天使用量计入本次签到');

  // 7. 详情：入口、安装内容、运行测试
  await packRow().click();
  await tid('pack-info').waitFor({ timeout: 10000 });
  await page.waitForTimeout(500);
  check((await tid('pack-info').textContent()).includes('安保队长'), '详情显示需求提出人');
  await page.locator('.el-drawer .el-tabs__item', { hasText: '安装内容' }).click();
  await page.waitForTimeout(300);
  const items = await tid('items-table').textContent();
  check(
    items.includes(ns + '.visitForm') && items.includes('migrations/V1__host_index.sql') && items.includes('状态机'),
    '安装内容列出视图、迁移、状态机'
  );
  await tid('btn-test').click();
  await tid('test-result').waitFor({ timeout: 60000 });
  check((await tid('test-result').textContent()).includes('全部通过'), '运行 Pack 测试：黄金场景与越权检查全部通过');

  // 8. 手工改表单标签 → 漂移 → 回灌
  psql(
    `update meta_view set schema_json = jsonb_set(schema_json, '{fields,1,label}', '"供应商名称"') where code = '${ns}.visitForm' and status = 'PUBLISHED'`
  );
  await gotoApps();
  check((await packRow().locator('[data-testid="pack-drift"]').textContent()).includes('1 项'), '列表显示配置漂移 1 项');
  await packRow().click();
  await tid('drift-table').waitFor({ timeout: 10000 });
  const dt = await tid('drift-table').textContent();
  check(
    dt.includes('/fields/1/label') && dt.includes('来访单位') && dt.includes('供应商名称'),
    '漂移报告列出差异：/fields/1/label 来访单位 → 供应商名称'
  );
  await tid('btn-drift-changeset').click();
  const generated = page.locator('.el-message--success', { hasText: '已生成 ChangeSet' });
  await generated.waitFor({ timeout: 30000 });
  const dmsg = await generated.textContent();
  const driftId = Number(/#(\d+)/.exec(dmsg)[1]);
  check(dmsg.includes('1.0.1'), '一键回灌为 ChangeSet #' + driftId + '（1.0.1）');
  const dcs = await api('GET', `/api/changesets/${driftId}`);
  check(
    dcs.data.source === 'DRIFT' && dcs.data.items.some((i) => i.type === 'DRIFT' && i.diffs[0].path === '/fields/1/label'),
    'ChangeSet 包含该差异'
  );
  const art = await api('GET', `/api/changesets/${driftId}/artifact`);
  check(art.code === 200 && art.data.sha256 === dcs.data.artifactSha, '回灌制品可下载，摘要一致');

  // 9. 停用：接口不可用，数据保留可导出；再启用
  await gotoApps();
  await packRow().click();
  await tid('btn-disable').click();
  await page.locator('.el-message-box .el-button--primary').click();
  await page.locator('.el-message--success', { hasText: '已停用' }).waitFor({ timeout: 10000 });
  const q = await api('POST', `/api/crud/${encodeURIComponent(concept)}/query`, {});
  check(q.data?.errorCode === 'PACK_DISABLED', '停用后台账接口不可访问：' + q.msg);
  const co = await api('POST', `/api/op/${concept}.checkOut`, { input: {}, targetId: String(created.data.id), version: allowed.data.version + 1 });
  check(co.data?.errorCode === 'PACK_DISABLED', '停用后 Operation 不可执行');
  const exp = await api('GET', `/api/packs/${encodeURIComponent(ns)}/export`);
  check(exp.data.data[concept].length >= 1, '停用后数据保留并可只读导出（' + exp.data.data[concept].length + ' 条）');
  await tid('btn-enable').click();
  await page.locator('.el-message--success', { hasText: '已启用' }).waitFor({ timeout: 10000 });
  check((await api('POST', `/api/crud/${encodeURIComponent(concept)}/query`, {})).code === 200, '重新启用后恢复访问');

  // 10. 复审：有使用的 Pack 到期为待复审；另一个从未使用的 Pack 到期且超过 90 天为建议下线，并通知负责人
  const idleNs = 'acme.idl' + suffix;
  const idleZip = build('1.0.0', {}, ownerParty, idleNs);
  const ic = (await api('POST', '/api/changesets', { archive: fs.readFileSync(idleZip).toString('base64') })).data;
  // 第二个 Pack 内容与已演练的示例相同：此处按演练脚本的接口回写阶段结果
  for (const st of ['SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE']) {
    await api('POST', `/api/changesets/${ic.id}/stages`, {
      stage: st,
      status: 'PASSED',
      artifactSha: ic.artifactSha,
      detail: { note: '同内容示例已演练' }
    });
  }
  await api('POST', `/api/changesets/${ic.id}/approve`, { approve: true, note: '复审场景' });
  check((await api('POST', `/api/changesets/${ic.id}/release`)).code === 200, '发布第二个（未使用的）Pack ' + idleNs);
  psql(`update meta_pack set review_due = date '2026-01-31' where code in ('${ns}', '${idleNs}')`);
  psql(`update meta_pack set installed_at = now() - interval '200 days' where code = '${idleNs}'`);
  await gotoApps();
  await tid('btn-review').click();
  await page.locator('.el-message--success', { hasText: '复审检查完成' }).waitFor({ timeout: 10000 });
  await page.waitForTimeout(800);
  check((await packRow().locator('[data-testid="pack-review-status"]').textContent()).includes('待复审'), '到期但近期有使用：待复审');
  const idleRow = page.locator('[data-testid="table-packs"] .el-table__body tr').filter({ hasText: idleNs }).first();
  check((await idleRow.locator('[data-testid="pack-review-status"]').textContent()).includes('建议下线'), '超过复审日期且 90 天无使用：标记建议下线');
  check(psql(`select review_status from meta_pack where code = '${idleNs}'`) === 'SUGGEST_RETIRE', 'meta_pack 复审状态 SUGGEST_RETIRE');
  const msgs = (await api('GET', '/api/workbench/messages')).data;
  check(
    msgs.some((m) => m.kind === 'PACK_REVIEW' && m.message.includes('建议下线')),
    '负责人在消息中心收到建议下线通知'
  );
  check(
    msgs.some((m) => m.kind === 'PACK_REVIEW' && m.message.includes('请复审')),
    '负责人收到待复审通知'
  );
  await idleRow.click();
  await tid('retire-hint').waitFor({ timeout: 10000 });
  check(true, '详情页提示建议下线');

  // 11. 迁移失败的升级：演练失败，门禁阻止发布并输出失败迁移
  const badZip = build('1.1.0', { 'migrations/V2__broken_column.sql': `alter table ${table} add column broken_col nosuchtype;\n` }, ownerParty);
  const sub = await api('POST', '/api/changesets', { archive: fs.readFileSync(badZip).toString('base64') });
  check(sub.code === 200 && sub.data.status === 'DRAFT', '提交 1.1.0（含错误迁移）ChangeSet #' + sub.data.id);
  const d2 = drill(sub.data.id);
  check(d2.code !== 0 && d2.out.includes('V2__broken_column.sql'), '升级演练失败并输出失败迁移 V2__broken_column.sql');
  const g = spawnSync(path.join(root, 'deploy/scripts/release-gate.sh'), [String(sub.data.id)], {
    env: { ...process.env, LSMOM_E2E_BASE: BASE },
    encoding: 'utf8'
  });
  check(g.status === 1 && g.stdout.includes('V2__broken_column.sql'), '发布门禁脚本拒绝并列出失败迁移');
  await gotoApps('变更发布');
  await tid(`btn-release-${sub.data.id}`).click();
  await page.locator('.el-message--error, .el-notification').first().waitFor({ timeout: 10000 });
  check(
    (await page.locator('.el-message--error, .el-notification').first().textContent()).includes('V2__broken_column.sql'),
    '界面发布被门禁阻止并显示失败迁移'
  );
  check(psql(`select version from meta_pack where code = '${ns}'`) === '1.0.0', '当前环境仍为 1.0.0');
  if (shot) await page.screenshot({ path: shot, fullPage: true });
  check(errors.length === 0, '页面无脚本错误' + (errors.length ? '：' + errors.join('; ') : ''));
} catch (e) {
  if (shot) await page.screenshot({ path: shot.replace(/\.png$/, '-fail.png'), fullPage: true });
  console.log('页面消息：', await page.locator('.el-message, .el-notification').allTextContents());
  throw e;
} finally {
  await browser.close();
  fs.rmSync(work, { recursive: true, force: true });
}
