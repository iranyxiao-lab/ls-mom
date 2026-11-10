import { defineAsyncComponent, type App, type Component } from 'vue';
import names from 'virtual:ep-icon-names';

/**
 * Element Plus 图标全局注册（RuoYi 页面按名称使用，如 <Edit />）。
 * 改为异步组件：图标包在第一次用到时才加载（单独分包），不进入首屏包。
 */
let pack: Promise<Record<string, Component>> | undefined;
const load = () => (pack ??= import('@element-plus/icons-vue') as unknown as Promise<Record<string, Component>>);

export default {
  install: (app: App) => {
    for (const key of names) {
      app.component(key, defineAsyncComponent(() => load().then((m) => m[key])));
    }
  }
};
