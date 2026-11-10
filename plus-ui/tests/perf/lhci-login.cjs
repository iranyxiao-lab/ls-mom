/**
 * Lighthouse CI 登录脚本（puppeteer-core）：用种子账号登录一次，后续审计的页面共享登录态。
 * 口令从 deploy/seed/README.md 读取（仅测试环境）。
 */
const fs = require('node:fs');
const path = require('node:path');

function seedPassword(user) {
  const row = fs.readFileSync(path.join(__dirname, '../../../deploy/seed/README.md'), 'utf8').split('\n').find((l) => l.startsWith(`| ${user} |`));
  return row.split('|')[4].trim();
}

let done = false;
module.exports = async (browser, context) => {
  // 审计登录页本身时保持未登录（URL 顺序中登录页在前）
  if (done || new URL(context.url).pathname === '/login') return;
  const base = new URL(context.url).origin;
  const page = await browser.newPage();
  const user = process.env.LHCI_USER ?? 'admin';
  for (let attempt = 1; ; attempt++) {
    await page.goto(base + '/login', { waitUntil: 'networkidle0' });
    // 登录页可能预填了账号口令：先全选再输入，避免追加
    for (const [sel, value] of [['input[type="text"][autocomplete="off"]', user], ['input[type="password"]', seedPassword(user)]]) {
      await page.$eval(sel, (el) => {
        el.value = '';
        el.dispatchEvent(new Event('input', { bubbles: true }));
      });
      await page.type(sel, value);
    }
    try {
      await Promise.all([page.waitForFunction(() => !location.pathname.includes('login'), { timeout: 20000 }), page.keyboard.press('Enter')]);
      break;
    } catch (e) {
      if (attempt >= 3) throw new Error(`${user} 登录失败：${e.message}`);
      await new Promise((r) => setTimeout(r, 3000 * attempt));
    }
  }
  await page.close();
  done = true;
};
