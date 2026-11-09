import request from '@/utils/request';
import { crudApi } from './crud';
import { formApi } from './form';
import { workbenchApi } from './workbench';
import type { Page, QuerySpec } from './types';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const key = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : String(Date.now() + Math.random()));

export * from './productionModel';
import type { BoardFilter, BoardWork, OpResult, Team } from './productionModel';

const op = (code: string, targetId: string | number | undefined, version: number | undefined, input: Record<string, unknown> = {}) =>
  crudApi.execute(code, { targetId: targetId === undefined ? undefined : String(targetId), version, input }, key()) as Promise<OpResult>;

export const productionApi = {
  board: (f: BoardFilter = {}) => data<BoardWork[]>(request({ url: '/api/production/board', method: 'get', params: f })),
  teams: () => data<Team[]>(request({ url: '/api/production/teams', method: 'get' })),
  myTeams: () => data<{ id: number; name: string }[]>(request({ url: '/api/production/my-teams', method: 'get' })),
  parties: (keyword?: string) => workbenchApi.parties(keyword),
  query: (concept: string, spec: QuerySpec) => crudApi.query(concept, spec) as Promise<Page>,
  get: (concept: string, id: number) => crudApi.get(concept, id) as Promise<Record<string, any>>,
  create: (concept: string, body: Record<string, unknown>) => crudApi.create(concept, body, key()),
  update: (concept: string, id: number, version: number, body: Record<string, unknown>) => crudApi.update(concept, id, version, body, key()),
  op,
  upload: (file: File) => formApi.upload(file),
  attach: (ossId: string | number, objectType: string, objectId: string | number, purpose = 'EVIDENCE') =>
    op('ls.core.DocumentLink.attach', undefined, undefined, { ossId: String(ossId), objectType, objectId: String(objectId), purpose }),
  docs: (objectType: string, objectId: string | number) =>
    data<Record<string, any>[]>(request({ url: '/api/doc', method: 'get', params: { objectType, objectId } }))
};

export type ProductionApi = typeof productionApi;
