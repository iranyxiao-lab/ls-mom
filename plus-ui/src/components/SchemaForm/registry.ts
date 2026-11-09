import type { Component } from 'vue';

/**
 * 前端自定义组件注册表：组件代码随 Pack 经评审与测试发布，后端 form_component 登记契约；
 * 表单只能通过 {code, version} 引用已注册组件，渲染器不执行任何来自 Schema 的代码。
 */
const components = new Map<string, Component>();

export function registerFormComponent(code: string, version: string, component: Component) {
  components.set(`${code}@${version}`, component);
}

export function resolveFormComponent(code?: string, version?: string): Component | undefined {
  return code && version ? components.get(`${code}@${version}`) : undefined;
}
