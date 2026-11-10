/** countClicks 自检：能统计点击数，键盘与输入不计入，超出预算时测试失败。 */
import { expect, test } from '@playwright/test';
import { countClicks } from '../support/clicks';
import { openStory } from './story';

test('统计点击数，键盘操作不计入', async ({ page }) => {
  await openStory(page, '设计系统-状态与反馈--badges-and-toast');
  const clicks = await countClicks(page);
  await clicks.reset();
  await page.getByTestId('toast-trigger').click();
  await page.getByTestId('toast-undo').click();
  await page.getByTestId('toast-trigger').focus();
  await page.keyboard.press('Enter');
  expect(await clicks.count()).toBe(2);
  expect(await clicks.expectWithin(2, '提交后撤销')).toBe(2);
});

test('超出预算时失败', async ({ page }) => {
  test.fail(true, '自检：3 次点击超过 2 次预算，断言必须失败');
  await openStory(page, '设计系统-状态与反馈--badges-and-toast');
  const clicks = await countClicks(page);
  await clicks.reset();
  for (let i = 0; i < 3; i++) await page.getByTestId('toast-trigger').click();
  await clicks.expectWithin(2, '超预算示例');
});
