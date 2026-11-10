import request from '@/utils/request';
import { crudApi } from './crud';
import { formApi } from './form';
import { workbenchApi } from './workbench';
import type { Page, QuerySpec } from './types';
import type { OpResult } from './productionModel';
import type { ObjectOption } from './qualityModel';

export * from './qualityModel';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const key = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : String(Date.now() + Math.random()));

const op = (code: string, targetId: string | number | undefined, version: number | undefined, input: Record<string, unknown> = {}) =>
  crudApi.execute(code, { targetId: targetId === undefined ? undefined : String(targetId), version, input }, key()) as Promise<OpResult>;

export const qualityApi = {
  objects: (type: string, keyword?: string) =>
    data<ObjectOption[]>(request({ url: '/api/quality/objects', method: 'get', params: { type, keyword } })),
  orgs: () => data<{ id: number; name: string; unit_type: string; parent_name?: string }[]>(request({ url: '/api/asset/orgs', method: 'get' })),
  rectifications: (ncId: number) => data<Record<string, any>[]>(request({ url: `/api/quality/nc/${ncId}/rectifications`, method: 'get' })),
  parties: (keyword?: string) => workbenchApi.parties(keyword),
  query: (concept: string, spec: QuerySpec) => crudApi.query(concept, spec) as Promise<Page>,
  get: (concept: string, id: number) => crudApi.get(concept, id) as Promise<Record<string, any>>,
  create: (concept: string, body: Record<string, unknown>) => crudApi.create(concept, body, key()),
  /** 不可撤销动作执行前的影响说明 */
  impact: (operation: string, targetId: string | number) => crudApi.impact(operation, targetId),
  op,
  upload: (file: File) => formApi.upload(file),
  attach: (ossId: string | number, objectType: string, objectId: string | number, purpose = 'EVIDENCE') =>
    op('ls.core.DocumentLink.attach', undefined, undefined, { ossId: String(ossId), objectType, objectId: String(objectId), purpose })
};

export type QualityApi = typeof qualityApi;
