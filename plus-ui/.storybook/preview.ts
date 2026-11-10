import type { Preview } from '@storybook/vue3-vite';
import { setup } from '@storybook/vue3-vite';
import ElementPlus from 'element-plus/es/defaults.mjs';
import 'element-plus/dist/index.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import 'vant/lib/index.css';
import 'virtual:uno.css';
import 'virtual:ls-tokens.css';
import '../src/design/base.css';
import { applyBrand, applyDensity, applyMode } from '../src/design/theme';

setup((app) => {
  app.use(ElementPlus);
});

const preview: Preview = {
  globalTypes: {
    theme: {
      description: '明暗主题',
      toolbar: { title: '主题', items: [{ value: 'light', title: '浅色' }, { value: 'dark', title: '深色' }], dynamicTitle: true }
    },
    density: {
      description: '密度',
      toolbar: { title: '密度', items: [{ value: 'comfortable', title: '舒适' }, { value: 'compact', title: '紧凑' }], dynamicTitle: true }
    },
    brand: {
      description: '品牌主色',
      toolbar: { title: '品牌色', items: [{ value: '', title: '默认' }, { value: '#0f8a6c', title: '客户绿' }, { value: '#b5371f', title: '客户红' }], dynamicTitle: true }
    }
  },
  initialGlobals: { theme: 'light', density: 'comfortable', brand: '' },
  decorators: [
    (story, ctx) => {
      applyMode(ctx.globals.theme === 'dark' ? 'dark' : 'light');
      applyDensity(ctx.globals.density === 'compact' ? 'compact' : 'comfortable');
      applyBrand(ctx.globals.brand || null);
      return { components: { story }, template: '<div style="padding: var(--ls-space-4); background: var(--ls-color-bg-page); min-height: 100vh"><story /></div>' };
    }
  ],
  parameters: { layout: 'fullscreen', controls: { expanded: true } }
};
export default preview;
