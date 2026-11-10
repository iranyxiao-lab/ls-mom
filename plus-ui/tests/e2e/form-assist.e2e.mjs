/**
 * modernize-ui-ux 8.3 端到端（无头 Chromium，部署环境）：自然语言生成字段与规则。
 *   在表单设计器新建设备缺陷表单 → 智能助手输入"严重度为高或严重时，必须填写现象" → 经 LlmGateway（录制的响应）生成补丁 →
 *   差异高亮（新增规则、修改字段说明）→ 确认前草稿与服务端都没有这条规则 → 应用后规则进入草稿，可撤销 → 校验通过、保存草稿；未发布。
 * 运行：node tests/e2e/form-assist.e2e.mjs（未配置 LSMOM_LLM_ENDPOINT 时使用 ls-mom-ai 内置录制）
 */
import { BASE, check, open } from './lib.mjs';

const run = Date.now().toString(36);
const code = `e2e.assist${run}.defectForm`;
const s = await open();
try {
  const { page } = s;
  await page.goto(`${BASE}/lsmom/form-designer/${code}?concept=ls.asset.Defect`);
  await page.getByTestId('designer').waitFor();
  await page.waitForTimeout(1500);
  const schemaText = async () => {
    await page.locator('#tab-schema').click();
    const t = await page.getByTestId('schema-text').inputValue();
    return JSON.parse(t);
  };
  check(!(await schemaText()).rules?.length, '新表单初稿没有规则');

  // —— 生成修改 ——
  await page.locator('#tab-assist').click();
  await page.getByTestId('assist-input').fill('严重度为高或严重时，必须填写现象');
  await page.getByTestId('assist-run').click();
  await page.getByTestId('assist-summary').waitFor({ timeout: 60000 });
  check(
    (await page.getByTestId('assist-model').textContent())?.includes('qwen2.5-72b-instruct@fp_rec01'),
    '经 LlmGateway 调用（录制的响应），记录模型与版本'
  );
  const op0 = page.getByTestId('assist-op-0');
  const t0 = (await op0.textContent()) ?? '';
  check(
    t0.includes('新增') && t0.includes('规则 symptomRequiredWhenSevere') && t0.includes("$model.severity == 'HIGH'"),
    '条件必填描述生成规则草稿（新增，含条件与断言）'
  );
  check((await op0.locator('.da').count()) >= 3, '新增内容高亮');
  const t1 = (await page.getByTestId('assist-op-1').textContent()) ?? '';
  check(t1.includes('修改') && t1.includes('help: null') && t1.includes('严重度为高或严重时必填'), '字段修改显示改前 → 改后');

  // —— 确认前不生效 ——
  check(!(await schemaText()).rules?.length, '确认前当前草稿仍没有规则');
  const hist = await s.api('GET', `/api/form/views/${encodeURIComponent(code)}/history`);
  check(hist.code !== 200 || !(hist.data ?? []).length, '确认前服务端没有这张表单的任何版本');

  // —— 应用 → 校验 → 保存草稿 ——
  await page.locator('#tab-assist').click();
  await page.getByTestId('assist-apply').click();
  await page.locator('.el-message--success', { hasText: '已应用 2 项到草稿' }).first().waitFor();
  const after = await schemaText();
  const rule = after.rules?.find((r) => r.code === 'symptomRequiredWhenSevere');
  check(rule && rule.field === 'symptom' && rule.when.includes('CRITICAL'), '应用后规则进入草稿');
  check(after.fields.find((f) => f.key === 'symptom')?.help === '严重度为高或严重时必填', '字段说明已修改');
  await page.locator('#tab-design').click();
  await page.getByTestId('undo').click();
  check(!(await schemaText()).rules?.length, '应用可撤销');
  await page.locator('#tab-design').click();
  await page.getByTestId('redo').click();
  check((await schemaText()).rules?.length === 1, '重做后规则回来');
  await page.locator('#tab-design').click();
  await page.getByTestId('check').click();
  await page.waitForTimeout(1500);
  check((await page.getByTestId('issues').count()) === 0, '服务端校验通过（规则表达式合法）');
  await page.getByTestId('save').click();
  await page.locator('.el-message--success').first().waitFor();
  const saved = await s.api('GET', `/api/form/views/${encodeURIComponent(code)}/history`);
  check(saved.code === 200 && saved.data.length === 1 && saved.data[0].status === 'DRAFT', '保存为草稿；未发布，不影响在用表单');
  check(s.errors.length === 0, '页面无脚本错误' + (s.errors.length ? '：' + s.errors.join(' | ') : ''));
} finally {
  await s.browser.close();
}
