import type { CrudApi } from './crud';

/** 按需加载真实接口，组件本身不静态依赖 request（便于独立渲染与测试）。 */
const load = () => import('./crud').then((m) => m.crudApi);

export const lazyCrudApi: CrudApi = new Proxy({} as CrudApi, {
  get:
    (_t, key: string) =>
    async (...args: unknown[]) =>
      ((await load()) as any)[key](...args)
});
