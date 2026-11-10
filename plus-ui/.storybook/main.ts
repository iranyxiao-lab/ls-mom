import type { StorybookConfig } from '@storybook/vue3-vite';

/** 组件目录与视觉回归基线（只构建静态站点：pnpm storybook:build；不在 CI 中启动带界面的浏览器）。 */
const config: StorybookConfig = {
  stories: ['../src/**/*.stories.ts'],
  addons: ['@storybook/addon-docs'],
  framework: { name: '@storybook/vue3-vite', options: {} },
  core: { disableTelemetry: true, disableWhatsNewNotifications: true },
  async viteFinal(cfg) {
    // 去掉只在应用中使用的插件（gzip 压缩、Vue DevTools），Storybook 不需要
    cfg.plugins = (cfg.plugins ?? []).flat().filter((p: any) => !p || !/compression|devtools/i.test(p.name ?? ''));
    cfg.server = { ...(cfg.server ?? {}), open: false };
    return cfg;
  }
};
export default config;
