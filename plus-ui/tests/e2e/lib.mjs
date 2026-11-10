/**
 * 端到端测试公共部分：无头 Chromium 登录部署环境、经前端同源调用接口。
 * 环境变量：LSMOM_E2E_BASE（默认 http://127.0.0.1:18081）；管理员口令从 deploy/seed/README.md 读取（仅测试环境）。
 */
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
export const BASE = process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081';
const env = fs.readFileSync(path.join(root, 'plus-ui/.env.production'), 'utf8');
export const CLIENT_ID = /^VITE_APP_CLIENT_ID\s*=\s*'([^']*)'/m.exec(env)[1];
export const API = /^VITE_APP_BASE_API\s*=\s*'([^']*)'/m.exec(env)[1];

function seedPassword(user) {
  const row = fs
    .readFileSync(path.join(root, 'deploy/seed/README.md'), 'utf8')
    .split('\n')
    .find((l) => l.startsWith(`| ${user} |`));
  return row.split('|')[4].trim();
}

/** 无头登录。user 为种子账号（默认 admin）；mobile=true 时模拟手机（触屏、移动 UA）。 */
export async function open({ width = 1600, height = 1000, user = 'admin', mobile = false, browser: shared } = {}) {
  const browser = shared ?? (await chromium.launch({ headless: true }));
  const context = await browser.newContext(
    mobile
      ? {
          viewport: { width: 390, height: 844 },
          isMobile: true,
          hasTouch: true,
          deviceScaleFactor: 2,
          userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36'
        }
      : { viewport: { width, height } }
  );
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  if (process.env.LSMOM_E2E_CONSOLE) page.on('console', (m) => console.log('[console]', m.type(), m.text().slice(0, 300)));
  if (process.env.LSMOM_E2E_CONSOLE) page.on('pageerror', (e) => console.log('[pageerror]', e.message));
  await page.goto(BASE + '/login');
  // 登录页初始化（租户、记住我）偶尔会覆盖刚填的值，失败时重填重试
  for (let attempt = 1; ; attempt++) {
    await page.waitForSelector('input[type="password"]', { timeout: 30000 });
    await page.waitForLoadState('networkidle').catch(() => undefined);
    await page.locator('input[type="text"][autocomplete="off"]').first().fill(user);
    await page.locator('input[type="password"]').fill(seedPassword(user));
    await page.locator('input[type="password"]').press('Enter');
    try {
      await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 });
      // 登录页本身的错误（如连续登录触发验证码接口限流）不计入被测页面
      errors.length = 0;
      break;
    } catch (e) {
      const shown = await page.locator('.el-message, .el-message-box, .el-form-item__error').allTextContents();
      if (attempt >= 3) throw new Error(`${user} 登录失败：${shown.join(' | ') || e.message}`);
      await page.goto(BASE + '/login');
    }
  }
  /** 以当前登录身份调用后端接口（同源，经 nginx 反代）。 */
  const api = (method, url, body) =>
    page.evaluate(
      async ([method, url, body, clientId, prefix]) => {
        const token = localStorage.getItem('Admin-Token');
        const r = await fetch(prefix + url, {
          method,
          headers: { 'Content-Type': 'application/json', clientid: clientId, Authorization: 'Bearer ' + token },
          body: body === undefined ? undefined : JSON.stringify(body)
        });
        return r.json();
      },
      [method, url, body, CLIENT_ID, API]
    );
  return { browser, context, page, api, errors };
}

export function check(cond, message) {
  if (!cond) throw new Error('断言失败：' + message);
  console.log('✓ ' + message);
}
