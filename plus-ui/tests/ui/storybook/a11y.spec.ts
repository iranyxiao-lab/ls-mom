/** 设计系统组件无障碍扫描：每个 Story 在浅色与深色下都不能有严重问题。 */
import fs from 'node:fs';
import { test } from '@playwright/test';
import { expectAccessible } from '../support/a11y';
import { openStory } from './story';

const index = JSON.parse(fs.readFileSync('storybook-static/index.json', 'utf8'));
const stories = Object.values(index.entries as Record<string, { id: string; type: string }>).filter((e) => e.type === 'story');

for (const s of stories) {
  for (const theme of ['light', 'dark']) {
    test(`${s.id}（${theme}）无严重无障碍问题`, async ({ page }) => {
      await openStory(page, s.id, { theme });
      await expectAccessible(page, `${s.id}（${theme}）`, { include: '#storybook-root' });
    });
  }
}
