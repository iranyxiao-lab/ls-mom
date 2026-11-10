import type { SavedViewApi } from './savedViews';

/** 按需加载保存视图接口，台账组件不静态依赖 request（便于独立渲染与测试）。 */
const load = () => import('./savedViews').then((m) => m.savedViewApi);

export const lazySavedViewApi: SavedViewApi = new Proxy({} as SavedViewApi, {
  get:
    (_t, key: string) =>
    async (...args: unknown[]) =>
      ((await load()) as any)[key](...args)
});
