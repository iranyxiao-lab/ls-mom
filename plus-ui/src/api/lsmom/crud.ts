import request from '@/utils/request';
import type { ExportJob, ListSchema, OperationCall, Page, QuerySpec } from './types';

/** 统一取 RuoYi 响应中的 data。 */
const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);

const enc = (concept: string) => encodeURIComponent(concept);

export const crudApi = {
  schema: (concept: string) => data<ListSchema>(request({ url: `/api/crud/${enc(concept)}/schema`, method: 'get' })),
  query: (concept: string, spec: QuerySpec) => data<Page>(request({ url: `/api/crud/${enc(concept)}/query`, method: 'post', data: spec })),
  get: (concept: string, id: number | string) => data<Record<string, unknown>>(request({ url: `/api/crud/${enc(concept)}/${id}`, method: 'get' })),
  create: (concept: string, body: Record<string, unknown>, idempotencyKey: string) =>
    data<{ id: number; version: number }>(
      request({ url: `/api/crud/${enc(concept)}`, method: 'post', data: body, headers: { 'Idempotency-Key': idempotencyKey } })
    ),
  update: (concept: string, id: number | string, version: number, body: Record<string, unknown>, idempotencyKey: string) =>
    data<{ id: number; version: number }>(
      request({
        url: `/api/crud/${enc(concept)}/${id}`,
        method: 'put',
        params: { version },
        data: body,
        headers: { 'Idempotency-Key': idempotencyKey }
      })
    ),
  remove: (concept: string, id: number | string, version: number) =>
    data<unknown>(request({ url: `/api/crud/${enc(concept)}/${id}`, method: 'delete', params: { version } })),
  copy: (concept: string, id: number | string) => data<{ id: number }>(request({ url: `/api/crud/${enc(concept)}/${id}/copy`, method: 'post' })),
  exportJob: (concept: string, spec: QuerySpec) => data<ExportJob>(request({ url: `/api/crud/${enc(concept)}/export`, method: 'post', data: spec })),
  /** 正式动作一律经 Operation Gateway。 */
  execute: (operation: string, call: OperationCall, idempotencyKey?: string) =>
    data<{ targetId: string; version: number; status: string; warnings: string[] }>(
      request({
        url: `/api/op/${operation}`,
        method: 'post',
        data: call,
        headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
      })
    )
};

export type CrudApi = typeof crudApi;
