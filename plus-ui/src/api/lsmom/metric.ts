import request from '@/utils/request';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const enc = encodeURIComponent;

export interface MetricCond {
  field: string;
  op: string;
  value?: unknown;
}

export interface MetricQuery {
  from?: string;
  to?: string;
  grain?: string;
  groupBy?: string[];
  filters?: MetricCond[];
  version?: number;
}

export interface MetricResult {
  code: string;
  version: number;
  name: string;
  unit?: string;
  fn: string;
  grain?: string;
  groupBy: string[];
  rows: Record<string, any>[];
}

export interface DrillRequest {
  from?: string;
  to?: string;
  grain?: string;
  bucket?: string;
  dims?: Record<string, unknown>;
  filters?: MetricCond[];
  numeratorOnly?: boolean;
  page?: number;
  size?: number;
  version?: number;
}

export interface DrillResult {
  code: string;
  version: number;
  concept: string;
  total: number;
  columns: string[];
  rows: Record<string, any>[];
}

export interface NamedQueryResult {
  code: string;
  version: number;
  columns: string[];
  rows: Record<string, any>[];
  truncated: boolean;
}

export const metricApi = {
  list: () => data<any[]>(request({ url: '/api/metrics', method: 'get' })),
  get: (code: string, version?: number) => data<any>(request({ url: `/api/metrics/${enc(code)}`, method: 'get', params: { version } })),
  // 查询类接口只读，可并发、重复调用
  query: (code: string, q: MetricQuery) =>
    data<MetricResult>(request({ url: `/api/metrics/${enc(code)}/query`, method: 'post', data: q, headers: { repeatSubmit: false } })),
  drill: (code: string, r: DrillRequest) =>
    data<DrillResult>(request({ url: `/api/metrics/${enc(code)}/drill`, method: 'post', data: r, headers: { repeatSubmit: false } })),
  namedQuery: (code: string, params: Record<string, unknown> = {}) =>
    data<NamedQueryResult>(request({ url: `/api/q/${enc(code)}`, method: 'post', data: params, headers: { repeatSubmit: false } }))
};

export type MetricApi = typeof metricApi;
