/** 自动化无障碍扫描（spec ui/ux-quality「自动化无障碍检查」）：WCAG 2.1 A/AA，严重（critical/serious）问题即失败。 */
import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

export const A11Y_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

export async function scan(page: Page, opts: { include?: string; exclude?: string[] } = {}) {
  let b = new AxeBuilder({ page }).withTags(A11Y_TAGS);
  if (opts.include) b = b.include(opts.include);
  for (const e of opts.exclude ?? []) b = b.exclude(e);
  const r = await b.analyze();
  return r.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious');
}

export function describeViolations(vs: Awaited<ReturnType<typeof scan>>) {
  return vs
    .map((v) => `[${v.impact}] ${v.id}：${v.help}\n` + v.nodes.slice(0, 5).map((n) => `    ${n.target.join(' ')}  ${n.failureSummary?.split('\n')[1] ?? ''}`).join('\n'))
    .join('\n');
}

export async function expectAccessible(page: Page, where: string, opts?: Parameters<typeof scan>[1]) {
  // 等对话框、抽屉的进场动画结束再检查（动画中途颜色是半透明的，对比度会被误判）
  await page
    .waitForFunction(() => document.getAnimations().every((a) => a.playState !== 'running' || a.effect?.getTiming().iterations === Infinity), null, {
      timeout: 3000
    })
    .catch(() => undefined);
  const vs = await scan(page, opts);
  expect(vs, `${where} 存在严重无障碍问题：\n${describeViolations(vs)}`).toEqual([]);
}
