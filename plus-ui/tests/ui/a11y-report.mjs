/** 无障碍问题明细（排查用）：node tests/ui/a11y-report.mjs /index /production/requests … */
import { chromium } from 'playwright';
import AxeBuilder from '@axe-core/playwright';
import { open, BASE } from '../e2e/lib.mjs';

const paths = process.argv.slice(2);
const { browser, page } = await open({ user: 'admin' });
const anon = await (await browser.newContext()).newPage();
for (const p of paths) {
  const pg = p === '/login' ? anon : page;
  await pg.goto(BASE + p);
  await pg.waitForLoadState('networkidle');
  await pg.waitForTimeout(500);
  const r = await new AxeBuilder({ page: pg }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  console.log('== ' + p);
  for (const v of r.violations.filter((v) => ['critical', 'serious'].includes(v.impact))) {
    console.log(`[${v.impact}] ${v.id}（${v.nodes.length}）`);
    for (const n of v.nodes.slice(0, 6)) console.log('   ', n.target.join(' '), '|', n.html.slice(0, 140).replace(/\s+/g, ' '), v.id === 'color-contrast' ? '| ' + (n.any[0]?.message ?? '').slice(0, 120) : '');
  }
}
await browser.close();
