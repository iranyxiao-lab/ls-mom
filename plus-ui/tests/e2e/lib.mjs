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
const CLIENT_ID = /^VITE_APP_CLIENT_ID\s*=\s*'([^']*)'/m.exec(env)[1];
const API = /^VITE_APP_BASE_API\s*=\s*'([^']*)'/m.exec(env)[1];

function seedPassword(user) {
  const row = fs
    .readFileSync(path.join(root, 'deploy/seed/README.md'), 'utf8')
    .split('\n')
    .find((l) => l.startsWith(`| ${user} |`));
  return row.split('|')[4].trim();
}

export async function open({ width = 1600, height = 1000 } = {}) {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width, height } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(BASE + '/login');
  await page.waitForSelector('input[type="password"]', { timeout: 30000 });
  await page.locator('input[type="text"][autocomplete="off"]').first().fill('admin');
  await page.locator('input[type="password"]').fill(seedPassword('admin'));
  await page.locator('input[type="password"]').press('Enter');
  await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 30000 });
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
  return { browser, page, api, errors };
}

export function check(cond, message) {
  if (!cond) throw new Error('断言失败：' + message);
  console.log('✓ ' + message);
}
