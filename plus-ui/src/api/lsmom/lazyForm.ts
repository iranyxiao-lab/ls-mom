import type { FormApi } from './form';

/** 按需加载真实接口，渲染器本身不静态依赖 request（便于独立渲染与测试）。 */
export const lazyFormApi: FormApi = new Proxy({} as FormApi, {
  get:
    (_t, key: string) =>
    async (...args: unknown[]) =>
      ((await import('./form').then((m) => m.formApi)) as any)[key](...args)
});
