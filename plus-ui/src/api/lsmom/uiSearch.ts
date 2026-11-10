import request from '@/utils/request';
import type { SearchHit } from './uiSearchModel';

export { objectPath, PARTY, WORK, type SearchHit } from './uiSearchModel';

export async function globalSearch(q: string, limit = 10): Promise<SearchHit[]> {
  const res: any = await request({ url: '/api/ui/search', method: 'get', params: { q, limit } });
  return (res.data ?? []) as SearchHit[];
}

export const getWork = (id: string) => request({ url: `/api/ui/objects/work/${id}`, method: 'get' }).then((r: any) => r.data);
export const getParty = (id: string) => request({ url: `/api/ui/objects/party/${id}`, method: 'get' }).then((r: any) => r.data);
