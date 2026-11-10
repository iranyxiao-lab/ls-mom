/**
 * modernize-ui-ux 8.2 端到端（无头 Chromium，部署环境）：从 Excel 生成台账。
 *   管理员上传点检表样例（点检日期、设备编号、点检人、结果、温度、缺陷数、备注）→ 浏览器内 SheetJS 读表头与前 50 行 →
 *   后端推断：日期、设备引用、人员引用、二值字典（正常/异常）、数字与必填 → 逐项核对（改对象名、改一个字段类型）→
 *   预览生成 → 草稿通过校验并带来源记录 → 演练 → 批准 → 发布 → 班组长按设备登记一条点检记录。
 * 运行：node tests/e2e/excel-ledger.e2e.mjs（只用于测试环境）
 */
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { BASE, check, open } from './lib.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const PROJECT = process.env.LSMOM_E2E_PROJECT ?? 'lsmom-verify';
const run = Date.now().toString(36);
const ns = 'cust.check' + run;
const CONCEPT = ns + '.CheckRecord';
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'lsmom-excel-ledger-'));

function drill(id) {
  const r = spawnSync(path.join(root, 'deploy/scripts/upgrade-drill.sh'), ['--changeset', String(id), '--backup-from', PROJECT], {
    env: { ...process.env, LSMOM_E2E_BASE: BASE },
    encoding: 'utf8',
    timeout: 20 * 60 * 1000
  });
  return { code: r.status, out: (r.stdout ?? '') + (r.stderr ?? '') };
}
const ok = async (p, what) => {
  const r = await p;
  if (r.code !== 200) throw new Error(`${what}失败：${r.msg}`);
  return r.data;
};

const admin = await open();
const sessions = [admin];
try {
  const { page } = admin;
  // —— 准备样例：真实设备编码与人员姓名 ——
  const orgs = await ok(admin.api('GET', '/api/asset/orgs'), '读组织');
  const teamA = orgs.find((o) => o.name === '一车间甲班');
  const assets = [];
  for (let i = 1; i <= 3; i++) {
    const a = await ok(
      admin.api('POST', '/api/crud/ls.asset.Asset', { assetCode: `XL-${run}-${i}`, name: `点检泵 ${run}-${i}`, assetType: 'PUMP', orgId: teamA.id }),
      '准备设备'
    );
    assets.push({ id: a.id, code: `XL-${run}-${i}` });
  }
  const people = ['郑师傅（甲班）', '吴班长（甲班）'];
  const rows = [['点检日期', '设备编号', '点检人', '结果', '温度（℃）', '缺陷数', '备注']];
  for (let i = 0; i < 60; i++) {
    rows.push([
      `2026-10-${String(1 + (i % 28)).padStart(2, '0')}`,
      assets[i % 3].code,
      people[i % 2],
      i % 6 === 0 ? '异常' : '正常',
      `${60 + (i % 9)}.5`,
      String(i % 3),
      i % 4 ? `第 ${i} 次` : ''
    ]);
  }
  const xlsx = path.join(work, '设备点检表.xlsx');
  execFileSync('python3', [path.join(here, 'make-xlsx.py'), xlsx, JSON.stringify(rows)]);

  // —— 1. 上传 → 推断 → 逐项核对 ——
  await page.goto(BASE + '/lsmom/apps');
  await page.locator('#tab-changes').click();
  await page.getByTestId('btn-from-template').click();
  const wz = page.getByTestId('tpl-wizard');
  await wz.getByTestId('tpl-excel-input').setInputFiles(xlsx);
  await wz.getByTestId('tpl-fields').waitFor({ timeout: 30000 });
  const type = async (code) => (await wz.getByTestId(`fld-type-${code}`).textContent())?.trim();
  check((await type('checkDate')) === '日期', '点检日期推断为日期');
  check((await type('asset')) === '引用', '设备编号推断为引用');
  const refText = await wz.getByTestId('fld-ref-asset').textContent();
  check(refText?.includes('设备'), '设备编号引用设备：' + refText?.trim());
  check((await wz.getByTestId('fld-why-asset').textContent())?.includes('现有设备'), '推断依据：取值能在现有设备中找到');
  check((await type('checker')) === '引用' && (await wz.getByTestId('fld-ref-checker').textContent())?.includes('人员'), '点检人推断为人员引用');
  check((await type('result')) === '选项', '结果推断为选项');
  const opts = await wz.getByTestId('fld-opts-result').inputValue();
  check(opts === '正常=OK, 异常=ABNORMAL', '结果为二值字典：' + opts);
  check((await type('temperature')) === '小数' && (await type('f6')) === '整数', '温度为小数、缺陷数为整数');
  check(await wz.getByTestId('fld-req-checkDate').locator('input').isChecked(), '点检日期全部有值，推断为必填');
  check(!(await wz.getByTestId('fld-req-remark').locator('input').isChecked()), '备注有空值，不必填');
  // 逐项修改：对象名、缺陷数编码
  await wz.getByTestId('tpl-entity').fill('CheckRecord');
  await wz.getByTestId('tpl-entity-name').fill('点检记录');
  await wz.getByTestId('fld-code-f6').fill('defects');
  await wz.getByTestId('tpl-next').click();
  await wz.getByTestId('tpl-info').waitFor();
  await wz.getByTestId('tpl-title').fill(`设备点检 ${run}`);
  await wz.getByTestId('tpl-ns').fill(ns);
  await wz.getByTestId('tpl-next').click();
  await wz.getByTestId('tpl-preview').waitFor();
  const names = await wz.locator('[data-testid^="tpl-file-"]:not([data-testid="tpl-file-editor"])').allTextContents();
  check(
    names.some((n) => n.trim() === 'provenance.json'),
    '生成文件含来源记录 provenance.json'
  );
  await wz.getByTestId('tpl-file-concepts/check-record.yaml').click();
  const concept = await wz.getByTestId('tpl-file-editor').inputValue();
  check(concept.includes('defects') && /refConcept: "?ls\.asset\.Asset/.test(concept), '核对时修改的编码进入 Concept，设备引用 ls.asset.Asset');
  await wz.getByTestId('tpl-create').click();
  await page.locator('.el-message--success', { hasText: '已生成草稿' }).first().waitFor({ timeout: 60000 });

  const cs = (await ok(admin.api('GET', `/api/changesets?pack=${encodeURIComponent(ns)}`), '读 ChangeSet'))[0];
  check(
    cs?.source === 'EXCEL' && cs.stages.some((s) => s.stage === 'VALIDATE' && s.status === 'PASSED'),
    `Excel 生成的草稿通过校验（ChangeSet #${cs?.id}，来源 EXCEL）`
  );

  // —— 2. 演练 → 批准 → 发布 → 使用 ——
  const d = drill(cs.id);
  if (d.code !== 0) console.log(d.out.slice(-4000));
  check(d.code === 0 && d.out.includes('门禁通过'), '升级演练通过');
  await ok(admin.api('POST', `/api/changesets/${cs.id}/approve`, { approve: true, note: 'Excel 台账评审通过' }), '批准');
  const rel = await ok(admin.api('POST', `/api/changesets/${cs.id}/release`), '发布');
  check(rel.status === 'INSTALLED', `发布 ${ns}：${rel.status}`);
  const prov = await ok(admin.api('GET', `/api/packs/${encodeURIComponent(ns)}/provenance`), '读来源');
  check(prov.sourceType === 'EXCEL' && prov.source?.app === '设备点检表.xlsx', '协同应用中心来源页：Excel 文件');
  check(String(prov.elements?.[CONCEPT]?.fields?.result?.field ?? '').includes('二值字典'), '来源页逐字段记录推断依据');

  const team = await open({ user: 'demo_team', browser: admin.browser });
  sessions.push(team);
  const me = await ok(team.api('GET', '/api/flow/me'), '读当前人员');
  const rec = await ok(
    team.api('POST', `/api/crud/${encodeURIComponent(CONCEPT)}`, {
      checkDate: '2026-11-11',
      asset: assets[0].id,
      checker: Number(me.partyId),
      result: 'ABNORMAL',
      temperature: 72.5,
      defects: 1
    }),
    '登记点检记录'
  );
  const back = await ok(team.api('GET', `/api/crud/${encodeURIComponent(CONCEPT)}/${rec.id}`), '读点检记录');
  check(back.result === 'ABNORMAL' && Number(back.asset) === Number(assets[0].id) && back.status === 'DRAFT', '班组长按设备登记点检记录（草稿）');
  const bad = await team.api('POST', `/api/crud/${encodeURIComponent(CONCEPT)}`, { asset: assets[0].id, checker: Number(me.partyId), result: 'OK' });
  check(bad.code !== 200 && JSON.stringify(bad).includes('checkDate'), '必填的点检日期缺失被拒');
  const errs = sessions.flatMap((s) => s.errors);
  check(errs.length === 0, '页面无脚本错误' + (errs.length ? '：' + errs.join(' | ') : ''));
} finally {
  await admin.browser.close();
}
