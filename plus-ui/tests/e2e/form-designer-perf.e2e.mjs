/**
 * 8.9 性能（无头 Chromium）：200 字段表单在设计器中拖入新控件，从 drop 到画布出现新控件的响应时间 ≤ 300ms。
 * 前置：经台账接口发布 200 个字段的台账；新表单初稿按主表字段生成，即 200 字段。
 * 运行：node tests/e2e/form-designer-perf.e2e.mjs（LSMOM_E2E_BASE 指定地址；LSMOM_PERF_BUDGET_MS 调整阈值）
 */
import { open, check, BASE } from './lib.mjs';

const FIELDS = 200;
const BUDGET = Number(process.env.LSMOM_PERF_BUDGET_MS ?? 300);
const ROUNDS = 5;
const { browser, page, api, errors } = await open({ width: 1600, height: 1000 });
try {
  const ns = 'acme.perf' + Date.now();
  const concept = ns + '.Big';
  const fields = Array.from({ length: FIELDS }, (_, i) => ({
    code: 'f' + i,
    displayName: '字段 ' + i,
    dataType: i % 5 === 0 ? 'INTEGER' : 'STRING',
    required: false,
    props: {}
  }));
  const r = await api('POST', '/api/ledger/publish', {
    packCode: 'acme.perf',
    master: { code: concept, displayName: '大表单', fields },
    children: []
  });
  check(r.code === 200, `发布 ${FIELDS} 字段台账`);

  await page.goto(`${BASE}/lsmom/form-designer/${encodeURIComponent(ns + '.bigForm')}?concept=${encodeURIComponent(concept)}`);
  const canvas = page.locator('[data-testid="designer"] ._fd-drag-box').first();
  await canvas.waitFor({ timeout: 60000 });
  await page.waitForFunction((n) => document.querySelectorAll('[data-testid="designer"] ._fd-drag-box ._fd-drag-item').length >= n, FIELDS, {
    timeout: 60000
  });
  check(true, `设计器载入 ${FIELDS} 个字段`);
  await page.waitForTimeout(1500);

  // 页内计时：drop 事件（捕获阶段）→ 画布控件数增加后的下一帧
  await page.evaluate(() => {
    const w = window;
    w.__perf = [];
    document.addEventListener(
      'drop',
      () => {
        const t0 = performance.now();
        const box = document.querySelector('[data-testid="designer"] ._fd-drag-box');
        const before = box.querySelectorAll(':scope > ._fd-drag-item').length;
        const obs = new MutationObserver(() => {
          if (box.querySelectorAll(':scope > ._fd-drag-item').length > before) {
            obs.disconnect();
            requestAnimationFrame(() => w.__perf.push(performance.now() - t0));
          }
        });
        obs.observe(box, { childList: true, subtree: false });
      },
      true
    );
  });

  const item = page.locator('._fc-l-item', { has: page.locator('._fc-l-name', { hasText: /^单行文本$/ }) }).first();
  for (let i = 0; i < ROUNDS; i++) {
    const target = canvas.locator(':scope > ._fd-drag-item').nth(i * 20);
    // 分步移动鼠标，SortableJS 才能在目标列表内算出插入位置
    await target.scrollIntoViewIfNeeded();
    await item.hover();
    await page.mouse.down();
    const b = await target.boundingBox();
    await page.mouse.move(b.x + b.width / 2, b.y + 5, { steps: 6 });
    await page.mouse.move(b.x + b.width / 2, b.y + b.height / 3, { steps: 4 });
    await page.mouse.up();
    await page.waitForFunction((n) => window.__perf.length >= n, i + 1, { timeout: 10000 });
  }
  const times = await page.evaluate(() => window.__perf);
  const max = Math.max(...times);
  console.log('拖入响应（ms）：' + times.map((t) => t.toFixed(1)).join(', '));
  check(max <= BUDGET, `200 字段表单拖拽响应最大 ${max.toFixed(1)}ms ≤ ${BUDGET}ms`);
  check(errors.length === 0, '页面无脚本错误' + (errors.length ? '：' + errors.join('；') : ''));
} catch (e) {
  console.error(e.message);
  process.exitCode = 1;
} finally {
  await browser.close();
}
