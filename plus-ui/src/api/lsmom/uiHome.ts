import request from '@/utils/request';
import type { LayoutCard } from '@/home/layout';

export interface HomeLayout {
  cards: LayoutCard[];
  /** personal 个人调整；role 角色默认 */
  source: 'personal' | 'role';
  roleKey?: string | null;
  roleCards: LayoutCard[];
}

export const homeApi = {
  get: (): Promise<HomeLayout> => request({ url: '/api/ui/home', method: 'get' }).then((r: any) => r.data),
  saveMine: (cards: LayoutCard[]) => request({ url: '/api/ui/home/mine', method: 'put', data: { cards } }),
  resetMine: () => request({ url: '/api/ui/home/mine', method: 'delete' }),
  roles: (): Promise<{ roleKey: string; roleName: string }[]> => request({ url: '/api/ui/home/roles', method: 'get' }).then((r: any) => r.data),
  saveRole: (roleKey: string, cards: LayoutCard[]) =>
    request({ url: `/api/ui/home/roles/${encodeURIComponent(roleKey)}`, method: 'put', data: { cards } })
};
