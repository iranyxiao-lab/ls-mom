/** 全局搜索结果（后端命名 Query ls.ui.globalSearch，已按权限与数据域过滤）。纯类型与函数，不依赖请求模块。 */
export interface SearchHit {
  object_type: string;
  object_id: string;
  code: string | null;
  title: string;
  subtitle: string | null;
  /** 0 编号完全一致，1 编号前缀，2 标题前缀，3 其他 */
  rank: number;
}

export const WORK = 'ls.core.Work';
export const PARTY = 'ls.core.Party';

/** 对象详情页地址：工作、人员走对象详情页，台账记录打开台账页详情。 */
export function objectPath(type: string, id: string | number): string {
  if (type === WORK) return `/lsmom/object/work/${id}`;
  if (type === PARTY) return `/lsmom/object/party/${id}`;
  return `/lsmom/ledger/${type}?id=${id}`;
}
