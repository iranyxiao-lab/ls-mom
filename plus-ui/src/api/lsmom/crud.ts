import request from '@/utils/request';
import type { ExportJob, ImportReport, ListSchema, OperationCall, OperationImpact, Page, QuerySpec, UndoInfo } from './types';

/** 统一取 RuoYi 响应中的 data。 */
const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);

/** 对象时间轴条目（按时间先后） */
export interface TimelineEntry {
  at: string;
  type: string;
  action: string;
  actorName?: string;
  fromStatus?: string;
  toStatus?: string;
  payload?: Record<string, any>;
  traceId?: string;
}

const enc = (concept: string) => encodeURIComponent(concept);

export const crudApi = {
  schema: (concept: string) => data<ListSchema>(request({ url: `/api/crud/${enc(concept)}/schema`, method: 'get' })),
  query: (concept: string, spec: QuerySpec) => data<Page>(request({ url: `/api/crud/${enc(concept)}/query`, method: 'post', data: spec })),
  /** 聚合（按当前筛选条件 count/max 等），列表"有 N 条更新"用它轮询 */
  aggregate: (concept: string, spec: QuerySpec) =>
    data<Record<string, unknown>[]>(
      request({ url: `/api/crud/${enc(concept)}/aggregate`, method: 'post', data: spec, headers: { silentError: true, repeatSubmit: false } })
    ),
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
  exportStatus: (jobId: number) => data<ExportJob>(request({ url: `/api/crud/export/${jobId}`, method: 'get' })),
  exportFile: (jobId: number) => request({ url: `/api/crud/export/${jobId}/download`, method: 'get', responseType: 'blob' }) as Promise<Blob>,
  importTemplate: (concept: string) =>
    request({ url: `/api/crud/${enc(concept)}/import/template`, method: 'get', responseType: 'blob' }) as Promise<Blob>,
  importPrecheck: (concept: string, file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return data<ImportReport>(
      // FormData 序列化后都是 {}，防重复提交会把连续两次不同文件的预检误判为重复
      request({
        url: `/api/crud/${enc(concept)}/import`,
        method: 'post',
        data: fd,
        headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false }
      })
    );
  },
  importConfirm: (concept: string, batchId: number) =>
    data<number>(request({ url: `/api/crud/${enc(concept)}/import/${batchId}/confirm`, method: 'post' })),
  timeline: (concept: string, id: number | string) =>
    data<TimelineEntry[]>(request({ url: `/api/timeline/${enc(concept)}/${id}`, method: 'get', headers: { silentError: true } })),
  /** 不可撤销动作执行前的影响说明（确认框内容）。 */
  impact: (operation: string, targetId: string | number) =>
    data<OperationImpact>(
      request({ url: '/api/op/impact', method: 'get', params: { operation, targetId: String(targetId) }, headers: { silentError: true } })
    ),
  /** 正式动作一律经 Operation Gateway。 */
  execute: (operation: string, call: OperationCall, idempotencyKey?: string) =>
    data<{ targetId: string; version: number; status: string; warnings: string[]; undo?: UndoInfo | null }>(
      request({
        url: `/api/op/${operation}`,
        method: 'post',
        data: call,
        headers: idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined
      })
    )
};

export type CrudApi = typeof crudApi;
