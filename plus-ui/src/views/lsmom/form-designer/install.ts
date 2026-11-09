import type { App } from 'vue';
import ElementPlus from 'element-plus';
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
