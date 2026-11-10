import type { App } from 'vue';
// 直接引入安装器模块（element-plus/es/defaults.mjs）：若从 'element-plus' 主入口引默认导出，
// 打包时全部组件会随主入口进入首屏包
import ElementPlus from 'element-plus/es/defaults.mjs';
import { FcDesigner, formCreate } from '@/vendor/form-create';

const installed = new WeakSet<App>();

/**
 * 设计器插件只在打开设计器页面时安装（不进入其余页面的首屏包）。
 * 应用对 Element Plus 按需引入，而设计器按名称解析全局组件（ElContainer、ElForm……），故此处全量注册。
 */
export function installDesigner(app: App) {
  if (installed.has(app)) return;
  app.use(ElementPlus);
  app.use(FcDesigner as any);
  app.use(formCreate);
  installed.add(app);
}
