/**
 * 8.6 端到端（无头）：在真实部署上用表单设计器拖出「主表＋子表」表单并发布。
 * 前置：通过台账接口发布一个带子 Concept 的借还单台账；之后全部经界面完成——
 * 拖入表格表单并绑定子 Concept → 拖入 3 个列并逐列绑定子 Concept 字段 → Schema 区可见子表 → 发布成功 → 运行时接口读到已发布版本。
 * 运行：node tests/e2e/form-designer.e2e.mjs（LSMOM_E2E_BASE 指定地址）
 */
import { open, check, BASE } from './lib.mjs';

const { browser, page, api, errors } = await open();
const shot = process.env.LSMOM_E2E_SHOT;
try {
  const ns = 'acme.fd' + Date.now();
  const order = ns + '.LoanOrder';
  const f = (code, displayName, dataType, required = false) => ({ code, displayName, dataType, required, props: {} });
  const r = await api('POST', '/api/ledger/publish', {
    packCode: 'acme.fd',
    master: {
      code: order,
      displayName: '工器具借还单',
      fields: [f('loanNo', '借用单号', 'STRING', true), f('borrower', '借用人', 'STRING', true), f('remark', '备注', 'TEXT')]
    },
    children: [
      {
        code: ns + '.LoanLine',
        displayName: '借出明细',
        fields: [f('tool', '工器具', 'STRING', true), f('qty', '数量', 'INTEGER', true), f('note', '说明', 'STRING')]
      }
    ]
  });
  check(r.code === 200, '发布借还单台账（含子 Concept）');

  const formCode = ns + '.loanForm';
  await page.goto(`${BASE}/lsmom/form-designer/${encodeURIComponent(formCode)}?concept=${encodeURIComponent(order)}`);
  const canvas = page.locator('[data-testid="designer"] ._fd-drag-box').first();
  await canvas.waitFor({ timeout: 30000 });
  await page.waitForTimeout(800);

  const paletteItem = (name) => page.locator('._fc-l-item', { has: page.locator('._fc-l-name', { hasText: new RegExp(`^${name}$`) }) }).first();
  const pick = async (testid, text) => {
    await page.locator(`[data-testid="${testid}"]`).locator('xpath=ancestor-or-self::*[contains(@class,"el-select")][1]').first().click();
    await page.locator('.el-select-dropdown__item:visible', { hasText: text }).first().click();
    await page.waitForTimeout(300);
  };

  // 1. 拖入表格表单，绑定子 Concept
  await paletteItem('表格表单').dragTo(canvas);
  const table = canvas.locator('._fd-drag-item', { has: page.locator('._fd-table-form') }).first();
  await table.waitFor({ timeout: 10000 });
  check((await canvas.locator('._fd-table-form').count()) === 1, '画布中出现 1 个表格表单');
  await table.locator('.fc-form-title').first().click();
  await page.locator('[data-testid="table-panel"]').waitFor({ timeout: 10000 });
  await pick('bind-table', '借出明细');
  check(true, '拖入表格表单并绑定子 Concept「借出明细」');

  // 2. 拖入 3 列并逐列绑定
  const cols = [
    ['tool', '工器具'],
    ['qty', '数量'],
    ['note', '说明']
  ];
  for (const [i, [code]] of cols.entries()) {
    if (i > 0) {
      // 在最后一列后追加新列
      await table.locator('._fd-tf-wrap > ._fd-drag-tool').last().locator('._fd-drag-r .icon-add').locator('xpath=..').dispatchEvent('click'); // 按钮仅悬停时显示
      await page.waitForTimeout(400);
    }
    const zone = table.locator('._fd-tableFormColumn-drag').last();
    await paletteItem('单行文本').dragTo(zone);
    await page.waitForTimeout(500);
    await pick('bind-field', `（${code}）`);
  }
  await page.waitForTimeout(800);

  // 3. Schema 区可见子表
  await page.locator('.el-tabs__item', { hasText: /^Schema$/ }).click();
  const text = await page.locator('[data-testid="schema-text"]').inputValue();
  const schema = JSON.parse(text);
  const sub = (schema.subTables ?? [])[0];
  check(sub && sub.key === 'loanLine' && sub.conceptCode === ns + '.LoanLine', 'Schema 中子表绑定 ' + (sub?.conceptCode ?? '无'));
  check(JSON.stringify(sub.fields.map((x) => x.key)) === JSON.stringify(cols.map((c) => c[0])), '子表列 = ' + sub.fields.map((x) => x.key).join(','));
  check(
    schema.layout.some((n) => n.type === 'subTable' && n.key === 'loanLine'),
    '布局包含子表节点'
  );
  await page.locator('.el-tabs__item', { hasText: /^设计$/ }).click();

  // 4. 发布
  await page.locator('[data-testid="publish"]').click();
  const result = page.locator('[data-testid="publish-result"]');
  await result.waitFor({ timeout: 20000 });
  check((await result.textContent()).includes('已发布'), '发布结果：' + (await result.textContent()).trim());

  const v = await api('GET', `/api/form/views/${encodeURIComponent(formCode)}`);
  check(
    v.code === 200 && v.data.status === 'PUBLISHED' && v.data.schema.subTables[0].fields.length === 3,
    `运行时读到已发布 v${v.data?.version}（子表 3 列）`
  );
  check(errors.length === 0, '页面无脚本错误' + (errors.length ? '：' + errors.join('；') : ''));
} catch (e) {
  if (shot) await page.screenshot({ path: shot });
  console.error(e.message);
  process.exitCode = 1;
} finally {
  await browser.close();
}
