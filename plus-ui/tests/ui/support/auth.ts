/**
 * 登录部署环境（无头）。口令从 deploy/seed/README.md 读取（仅测试环境），登录态按用户缓存，避免触发登录限流。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Browser, BrowserContext, BrowserContextOptions, Page } from '@playwright/test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
const env = fs.readFileSync(path.join(root, 'plus-ui/.env.production'), 'utf8');
export const CLIENT_ID = /^VITE_APP_CLIENT_ID\s*=\s*'([^']*)'/m.exec(env)![1];
export const API = /^VITE_APP_BASE_API\s*=\s*'([^']*)'/m.exec(env)![1];
// 不放在 test-results 下（每次运行会被清空）
const STATE_DIR = path.join(root, 'plus-ui/node_modules/.cache/ls-ui-auth');

function seedPassword(user: string) {
  const row = fs
    .readFileSync(path.join(root, 'deploy/seed/README.md'), 'utf8')
    .split('\n')
    .find((l) => l.startsWith(`| ${user} |`));
  if (!row) throw new Error('种子账号不存在：' + user);
  return row.split('|')[4].trim();
}

async function loginOnPage(page: Page, user: string) {
  await page.goto('/login');
  for (let attempt = 1; ; attempt++) {
    await page.waitForSelector('input[type="password"]', { timeout: 30_000 });
    await page.waitForLoadState('networkidle').catch(() => undefined);
    await page.locator('input[type="text"][autocomplete="off"]').first().fill(user);
    await page.locator('input[type="password"]').fill(seedPassword(user));
    await page.locator('input[type="password"]').press('Enter');
    try {
      await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20_000 });
      return;
    } catch (e) {
      if (attempt >= 3) throw new Error(`${user} 登录失败：${(e as Error).message}`);
      await page.waitForTimeout(3000 * attempt);
      await page.goto('/login');
    }
  }
}

/** 以种子账号登录的新上下文（同一用户 30 分钟内复用登录态）。 */
export async function loggedIn(browser: Browser, user = 'admin', opts: BrowserContextOptions = {}): Promise<{ context: BrowserContext; page: Page }> {
  fs.mkdirSync(STATE_DIR, { recursive: true });
  const file = path.join(STATE_DIR, `${user}.json`);
  const fresh = fs.existsSync(file) && Date.now() - fs.statSync(file).mtimeMs < 30 * 60_000;
  const baseURL = process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081';
  const context = await browser.newContext({ baseURL, ...opts, ...(fresh ? { storageState: file } : {}) });
  const page = await context.newPage();
  if (fresh) {
    await page.goto('/index');
    if (!page.url().includes('login')) return { context, page };
  }
  await loginOnPage(page, user);
  await context.storageState({ path: file });
  return { context, page };
}

/** 以当前登录身份调用后端接口。 */
/** 不复用任何登录态与本地存储的新上下文（相当于换一个浏览器登录）。 */
export async function freshLogin(browser: Browser, user: string, opts: BrowserContextOptions = {}): Promise<{ context: BrowserContext; page: Page }> {
  const baseURL = process.env.LSMOM_E2E_BASE ?? 'http://127.0.0.1:18081';
  const context = await browser.newContext({ baseURL, ...opts });
  const page = await context.newPage();
  await loginOnPage(page, user);
  return { context, page };
}

export function api(page: Page) {
  return <T = any>(method: string, url: string, body?: unknown): Promise<T> =>
    page.evaluate(
      async ([method, url, body, clientId, prefix]) => {
        const r = await fetch(prefix + url, {
          method,
          headers: { 'Content-Type': 'application/json', clientid: clientId, Authorization: 'Bearer ' + localStorage.getItem('Admin-Token') },
          body: body === undefined ? undefined : JSON.stringify(body)
        });
        return r.json();
      },
      [method, url, body, CLIENT_ID, API] as const
    );
}
