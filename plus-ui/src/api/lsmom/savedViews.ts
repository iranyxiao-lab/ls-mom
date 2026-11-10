import request from '@/utils/request';
import type { FilterOp } from './types';
import type { ColumnPref } from '@/components/SchemaTable/columnState';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);

export interface ViewFilter {
  field: string;
  op: FilterOp;
  value?: unknown;
}

/** 视图定义：筛选（可含 $context.orgId / $context.partyId）、列、排序、分组。 */
export interface ViewDefinition {
  filters: ViewFilter[];
  columns: ColumnPref[];
  sort: { field: string; dir: 'asc' | 'desc' }[];
  groupBy?: string | null;
  keyword?: string;
}

export interface SavedView {
  id: number;
  name: string;
  mine: boolean;
  sharedRole?: string | null;
  definition: ViewDefinition;
}

export interface ViewContext {
  orgId?: number | null;
  partyId?: number | null;
}

export const savedViewApi = {
  list: (concept: string) =>
    data<{ views: SavedView[]; defaultId: number | null }>(request({ url: '/api/ui/saved-views', method: 'get', params: { concept } })),
  create: (body: { concept: string; name: string; definition: ViewDefinition; sharedRole?: string | null }) =>
    data<{ id: number }>(request({ url: '/api/ui/saved-views', method: 'post', data: body })),
  update: (id: number, body: { name: string; definition: ViewDefinition; sharedRole?: string | null }) =>
    data<void>(request({ url: `/api/ui/saved-views/${id}`, method: 'put', data: body })),
  remove: (id: number) => data<void>(request({ url: `/api/ui/saved-views/${id}`, method: 'delete' })),
  setDefault: (concept: string, viewId: number | null) =>
    data<void>(request({ url: '/api/ui/saved-views/default', method: 'put', data: { concept, viewId } })),
  /** 角色（共享视图用，需要 lsmom:ui:view:share；没有权限时为空） */
  roles: () =>
    data<{ roleKey: string; roleName: string }[]>(request({ url: '/api/ui/saved-views/roles', method: 'get', headers: { silentError: true } })).catch(
      () => []
    ),
  /** 当前用户的组织与人员（解析视图里的 $context.*） */
  context: () => data<ViewContext>(request({ url: '/api/ui/form-context', method: 'get' }))
};

export type SavedViewApi = typeof savedViewApi;
