/**
 * 走查夹具（acceptance-walkthrough 2.2）：
 * - walk.as(账号)：以验收账号登录（口令取自 deploy/seed/acceptance/accounts.yaml，只在内存中使用），每个账号一个独立浏览器上下文；
 * - walk.step(标题, 动作)：包成 Playwright step，结束（含失败）时对当前页面截图，编号保存到本用例的证据目录；
 * - 每一步经过的接口响应中的 X-Trace-Id 记入 traces.json；
 * - 失败时保留每个账号的录像（video/<账号>.webm）与 Playwright trace（trace.zip），通过时删除；
 * - 测试标题以 [AC-xxx] 开头，tools/collect.ts 据此关联用例。
 */
import fs from 'node:fs';
import path from 'node:path';
import { test as base, expect, type BrowserContext, type Page } from '@playwright/test';
import { accPassword } from '../../tools/seed-sql.ts';
import { CLIENT_ID, API_PREFIX } from './env.ts';

export interface Session {
  account: string;
  context: BrowserContext;
  page: Page;
  /** 以该账号调用后端接口（同源 fetch），返回 { status, traceId, body } */
  api: <T = any>(method: string, url: string, body?: unknown) => Promise<{ status: number; traceId: string | null; body: T }>;
}

interface StepRecord {
  no: number;
  title: string;
  account?: string;
  ok: boolean;
  screenshot?: string;
  traceIds: string[];
  startedAt: string;
  ms: number;
}

export interface Walk {
  /** 以账号登录（同一账号复用会话）；fresh：重新登录（如被授权后旧会话失效） */
  as(account: string, opts?: { mobile?: boolean; fresh?: boolean }): Promise<Session>;
  step<T>(title: string, fn: () => Promise<T>, on?: Session): Promise<T>;
  /** 本用例证据目录 */
  dir: string;
  steps: StepRecord[];
}

const slug = (s: string) => s.replace(/[\\/:*?"<>|\s]+/g, '-').slice(0, 60);

export const test = base.extend<{ walk: Walk }>({
  walk: async ({ browser, baseURL }, use, info) => {
    const dir = info.outputDir;
    fs.mkdirSync(dir, { recursive: true });
    const sessions: Session[] = [];
    const steps: StepRecord[] = [];
    let current: StepRecord | null = null;
    let last: Session | null = null;

    const as: Walk['as'] = async (account, opts = {}) => {
      const existing = sessions.find((s) => s.account === account);
      if (existing && !opts.fresh) return (last = existing);
      if (existing) sessions.splice(sessions.indexOf(existing), 1);
      // trace 由 Playwright 按配置（retain-on-failure）记录；录像由本夹具记录，用例通过时删除
      const context = await browser.newContext({
        baseURL,
        recordVideo: { dir: path.join(dir, 'video'), size: { width: 1280, height: 800 } },
        ...(opts.mobile ? { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } : { viewport: { width: 1440, height: 900 } })
      });
      const page = await context.newPage();
      page.on('response', (r) => {
        const t = r.headers()['x-trace-id'];
        if (t && current && !current.traceIds.includes(t)) current.traceIds.push(t);
      });
      await page.goto('/login');
      for (let attempt = 1; ; attempt++) {
        await page.waitForSelector('input[type="password"]', { timeout: 30_000 });
        await page.waitForLoadState('networkidle').catch(() => undefined);
        await page.locator('input[type="text"][autocomplete="off"]').first().fill(account);
        await page.locator('input[type="password"]').fill(accPassword());
        await page.locator('input[type="password"]').press('Enter');
        try {
          await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20_000 });
          break;
        } catch (e) {
          if (attempt >= 3) throw new Error(`${account} 登录失败：${(e as Error).message}`);
          await page.waitForTimeout(2000 * attempt);
          await page.goto('/login');
        }
      }
      const api: Session['api'] = async (method, url, body) =>
        page.evaluate(
          async ([method, url, body, clientId, prefix]) => {
            const r = await fetch(prefix + url, {
              method,
              headers: { 'Content-Type': 'application/json', clientid: clientId, Authorization: 'Bearer ' + localStorage.getItem('Admin-Token') },
              body: body === undefined ? undefined : JSON.stringify(body)
            });
            const text = await r.text();
            let parsed: unknown = text;
            try {
              parsed = JSON.parse(text);
            } catch {}
            return { status: r.status, traceId: r.headers.get('x-trace-id'), body: parsed as any };
          },
          [method, url, body, CLIENT_ID, API_PREFIX] as const
        );
      // 新手引导会盖住页面：验收账号统一标记为已跳过
      await api('PUT', '/api/ui/prefs', { tourWorkbench: 'skipped', tourDesigner: 'skipped', tourMobileReport: 'skipped' }).catch(() => undefined);
      const s: Session = { account, context, page, api };
      sessions.push(s);
      return (last = s);
    };

    const step: Walk['step'] = async (title, fn, on) =>
      base.step(title, async () => {
        const rec: StepRecord = { no: steps.length + 1, title, ok: false, traceIds: [], startedAt: new Date().toISOString(), ms: 0 };
        steps.push(rec);
        current = rec;
        const t0 = Date.now();
        const shot = async (suffix: string) => {
          const s = on ?? last;
          if (!s) return;
          rec.account = s.account;
          const file = path.join(dir, `step-${String(rec.no).padStart(2, '0')}-${slug(title)}${suffix}.png`);
          await s.page.screenshot({ path: file, fullPage: false }).catch(() => undefined);
          if (fs.existsSync(file)) {
            rec.screenshot = path.relative(dir, file);
            await info.attach(`第 ${rec.no} 步：${title}`, { path: file, contentType: 'image/png' });
          }
        };
        try {
          const out = await fn();
          rec.ok = true;
          await shot('');
          return out;
        } catch (e) {
          await shot('-失败');
          throw e;
        } finally {
          rec.ms = Date.now() - t0;
          current = null;
        }
      });

    await use({ as, step, dir, steps });

    for (const s of sessions) await s.context.close().catch(() => undefined);
    if (info.status === info.expectedStatus) fs.rmSync(path.join(dir, 'video'), { recursive: true, force: true });
    else {
      // 录像按账号命名，便于对照
      for (const s of sessions) {
        const v = await s.page.video()?.path().catch(() => undefined);
        if (v && fs.existsSync(v)) fs.renameSync(v, path.join(dir, 'video', `${s.account}.webm`));
      }
    }
    const traces = path.join(dir, 'traces.json');
    fs.writeFileSync(traces, JSON.stringify({ title: info.title, status: info.status, steps }, null, 2));
    await info.attach('traces.json', { path: traces, contentType: 'application/json' });
  }
});

export { expect };
