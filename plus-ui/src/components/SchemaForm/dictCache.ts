import type { Option } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';

/** 字典选项按接口对象与字典类型缓存 1 分钟：子表 30 行同一字典列只请求一次。 */
const cache = new WeakMap<object, Map<string, { at: number; p: Promise<Option[]> }>>();
const TTL = 60_000;

export function dictOptions(api: RendererApi, type: string): Promise<Option[]> {
  let m = cache.get(api);
  if (!m) cache.set(api, (m = new Map()));
  const hit = m.get(type);
  if (hit && Date.now() - hit.at < TTL) return hit.p;
  const p = api.dict(type).catch(() => {
    m!.delete(type);
    return [] as Option[];
  });
  m.set(type, { at: Date.now(), p });
  return p;
}
