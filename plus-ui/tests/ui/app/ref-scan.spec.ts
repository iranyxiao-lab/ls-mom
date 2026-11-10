/**
 * modernize-ui-ux 4.7：扫码（部署环境，无头，模拟摄像头视频流）。
 * 班长在「我的点检」点「扫码点检」→ 摄像头画面里是设备二维码 → 直接打开该设备分给自己的待点检清单；
 * 不开摄像头时用扫码枪扫（快速输入以 Enter 结束）同样打开；扫到没有分给自己的设备、不存在的编码给出原因。
 * 设备员在缺陷登记表单的设备字段里用扫码枪扫另一台设备的编码 → 设备字段改为该设备；扫到不存在的编码在字段下提示。
 */
import fs from 'node:fs';
import { chromium, expect, test, type Browser } from '@playwright/test';
import { api, freshLogin } from '../support/auth';
import { expectAccessible } from '../support/a11y';
import { qrVideo } from '../support/fakeCamera';

const PLAN = 'ls.asset.InspectionPlan';
const RECORD = 'ls.asset.InspectionRecord';
test.describe.configure({ timeout: 180_000 });

let video = '';
let cam: Browser | null = null;
test.afterAll(async () => {
  await cam?.close();
  if (video) fs.rmSync(video, { force: true });
});

test('扫码点检：摄像头扫设备码打开待点检清单；扫码枪同样可用；无任务、无此设备给出原因', async ({ browser }) => {
  const keeper = await freshLogin(browser, 'demo_asset', { viewport: { width: 1440, height: 900 } });
  await keeper.page.goto('/index');
  const k = api(keeper.page);
  const assets = (
    await k('POST', '/api/crud/ls.asset.Asset/query', { filters: [{ field: 'status', op: 'eq', value: 'RUNNING' }], page: 1, size: 50 })
  ).data.rows as any[];
  const leader = await freshLogin(browser, 'demo_team');
  await leader.page.goto('/index');
  const me = (await api(leader.page)('GET', '/api/flow/me')).data;
  // 选班长名下没有待点检的设备，扫码结果才确定
  const drafts = (await api(leader.page)('GET', '/api/asset/inspections/mine?status=DRAFT')).data as any[];
  await leader.context.close();
  const free = assets.filter((a) => !drafts.some((d) => d.asset_id === a.id));
  expect(free.length, '至少两台班长名下没有待点检的在用设备').toBeGreaterThan(1);
  const [asset, other] = free;

  // 设备员给班长建一个点检计划并立即生成点检工作
  const name = `扫码点检 ${Date.now().toString(36)}`;
  const created = await k('POST', `/api/crud/${PLAN}`, {
    name,
    asset: asset.id,
    cycle: 'DAILY',
    responsible: me.partyId,
    nextDue: new Date().toISOString().slice(0, 10),
    orgId: asset.orgId,
    inspectionPlanItem: [
      { itemCode: 'SCANA', name: '扫码检查项·外观', valueType: 'CHECK' },
      { itemCode: 'SCANT', name: '扫码检查项·温度', valueType: 'NUMBER', unit: '℃', upperLimit: 70 }
    ]
  });
  expect(created.code, JSON.stringify(created)).toBe(200);
  const execOp = async (op: string, id: number) => {
    const cur = (await k('GET', `/api/crud/${PLAN}/${id}`)).data;
    return k('POST', `/api/op/${op}`, { targetId: String(id), version: cur.version, input: {} });
  };
  try {
    expect((await execOp(`${PLAN}.activate`, created.data.id)).code).toBe(200);
    expect((await execOp(`${PLAN}.generate`, created.data.id)).code).toBe(200);
    const rec = (await k('POST', `/api/crud/${RECORD}/query`, { filters: [{ field: 'plan', op: 'eq', value: created.data.id }], page: 1, size: 1 }))
      .data.rows[0];
    expect(rec?.status).toBe('DRAFT');

    // 班长：模拟摄像头画面里是设备的二维码（链接形式）
    video = qrVideo(`https://mom.example.com/asset/${encodeURIComponent(asset.assetCode)}`);
    cam = await chromium.launch({
      headless: true,
      args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', `--use-file-for-fake-video-capture=${video}`]
    });
    const t = await freshLogin(cam, 'demo_team', { viewport: { width: 1440, height: 900 }, permissions: ['camera'] });
    await t.page.goto('/asset/inspection');
    await expect(t.page.getByTestId('inspection-desk')).toBeVisible();
    await t.page.getByTestId('btn-scan-inspect').click();
    await expect(t.page.getByTestId('scan-dialog')).toBeVisible();
    const items = t.page.getByTestId('ins-items');
    await expect(items).toBeVisible({ timeout: 20_000 });
    await expect(items).toContainText('扫码检查项·外观');
    await expect(t.page.locator('.el-drawer__title', { hasText: '点检：' })).toContainText(`点检：${asset.assetCode} ${asset.name}`);
    await expect(items).toContainText('扫码检查项·温度');
    await expect(t.page.getByTestId('scan-dialog')).toBeHidden();
    // 摄像头已关闭
    expect(await t.page.evaluate(() => [...document.querySelectorAll('video')].every((v) => !(v.srcObject as MediaStream | null)?.active))).toBe(
      true
    );
    await expectAccessible(t.page, '扫码打开的点检清单', { include: '.el-drawer' });
    await t.page.keyboard.press('Escape');
    await expect(items).toBeHidden();

    // 扫码枪：焦点不在输入框时直接扫
    await t.page.getByTestId('inspection-desk').getByRole('tab', { name: '我的点检' }).click();
    await t.page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    await t.page.keyboard.type(`${asset.assetCode}\n`, { delay: 5 });
    await expect(items).toBeVisible();
    await expect(items).toContainText('扫码检查项·外观');
    // 填写并提交：扫码打开的就是可填写的点检
    await t.page.getByTestId('ins-ok-SCANA').click();
    await t.page.getByTestId('ins-value-SCANT').locator('input').fill('45');
    await t.page.getByTestId('btn-ins-submit').click();
    await expect(t.page.locator('.el-message').filter({ hasText: '点检已提交，全部正常' }).first()).toBeVisible();
    expect((await api(t.page)('GET', `/api/crud/${RECORD}/${rec.id}`)).data).toMatchObject({ status: 'SUBMITTED', result: 'NORMAL' });
    await t.page.keyboard.press('Escape');
    await expect(items).toBeHidden();

    // 已提交后再扫：没有待点检
    await t.page.keyboard.type(`${asset.assetCode}\n`, { delay: 5 });
    await expect(t.page.locator('.el-message').filter({ hasText: '没有分给你的待点检' }).first()).toBeVisible();

    // 没有分给自己的设备（班长看不到车间设备台账时提示找不到或无权）、不存在的编码
    await t.page.keyboard.type(`${other.assetCode}\n`, { delay: 5 });
    await expect(
      t.page
        .locator('.el-message')
        .filter({ hasText: new RegExp(`没有分给你的待点检|没有找到编码为「${other.assetCode}」的设备，或你无权使用`) })
        .first()
    ).toBeVisible();
    await expect(items).toBeHidden();
    await t.page.keyboard.type('NO-SUCH-ASSET-9\n', { delay: 5 });
    await expect(t.page.locator('.el-message').filter({ hasText: '没有找到编码为「NO-SUCH-ASSET-9」' }).first()).toBeVisible();
    await t.context.close();

    // 设备员：缺陷登记表单的设备字段，扫码枪扫另一台设备
    await keeper.page.goto('/asset/center');
    await keeper.page.getByTestId(`asset-row-${asset.id}`).click();
    await keeper.page.getByTestId('btn-asset-defect').click();
    const dlg = keeper.page.getByTestId('defect-dialog');
    await expect(dlg.getByTestId('field-asset')).toContainText(asset.name);
    await expect(dlg.getByTestId('scan-btn-ls.asset.Asset')).toBeVisible();
    await dlg.getByTestId('input-asset').click();
    await keeper.page.keyboard.type(`${other.assetCode}\n`, { delay: 5 });
    await expect(dlg.getByTestId('field-asset')).toContainText(other.name);
    await dlg.getByTestId('input-asset').click();
    await keeper.page.keyboard.type('NO-SUCH-ASSET-9\n', { delay: 5 });
    await expect(dlg.locator('[data-field-key="asset"]')).toContainText('没有找到编码为「NO-SUCH-ASSET-9」');
    await expect(dlg.getByTestId('field-asset')).toContainText(other.name);
  } finally {
    await execOp(`${PLAN}.pause`, created.data.id).catch(() => undefined);
    await keeper.context.close();
  }
});
