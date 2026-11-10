import request from '@/utils/request';
import { crudApi } from './crud';
import { formApi } from './form';
import { workbenchApi } from './workbench';
import { dcsApi } from './dcs';
import type { DcsPoint } from './dcsModel';
import { checkPermi } from '@/utils/permission';
import type { Page, QuerySpec } from './types';
import type { FormSchema } from '@/types/lsView';
import type { OpResult } from './productionModel';
import type { MyInspection, OrgOption, TimelineItem } from './assetModel';

export * from './assetModel';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const key = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : String(Date.now() + Math.random()));

const op = (code: string, targetId: string | number | undefined, version: number | undefined, input: Record<string, unknown> = {}) =>
  crudApi.execute(code, { targetId: targetId === undefined ? undefined : String(targetId), version, input }, key()) as Promise<OpResult>;

export const assetApi = {
  timeline: (id: number) => data<TimelineItem[]>(request({ url: `/api/asset/assets/${id}/timeline`, method: 'get' })),
  myInspections: (status = 'DRAFT') => data<MyInspection[]>(request({ url: '/api/asset/inspections/mine', method: 'get', params: { status } })),
  /** 扫设备码：按设备编码找到设备（无权查看或找不到时报错） */
  scanAsset: (code: string) => formApi.refScan({ ref: { concept: 'ls.asset.Asset' }, code }),
  orgs: () => data<OrgOption[]>(request({ url: '/api/asset/orgs', method: 'get' })),
  parties: (keyword?: string) => workbenchApi.parties(keyword),
  query: (concept: string, spec: QuerySpec) => crudApi.query(concept, spec) as Promise<Page>,
  get: (concept: string, id: number) => crudApi.get(concept, id) as Promise<Record<string, any>>,
  create: (concept: string, body: Record<string, unknown>) => crudApi.create(concept, body, key()),
  /** 不可撤销动作执行前的影响说明 */
  impact: (operation: string, targetId: string | number) => crudApi.impact(operation, targetId),
  op,
  upload: (file: File) => formApi.upload(file),
  /** 已发布的缺陷登记表单（带入设备、位置、登记人、登记时间，记住上次的严重度） */
  defectForm: () => formApi.get<FormSchema>('ls.asset.defectForm').then((v) => v.schema),
  attach: (ossId: string | number, objectType: string, objectId: string | number, purpose = 'EVIDENCE') =>
    op('ls.core.DocumentLink.attach', undefined, undefined, { ossId: String(ossId), objectType, objectId: String(objectId), purpose }),
  /** 映射到该设备的 DCS 测点及最近快照；无 DCS 查看权限时为空。 */
  points: (assetId: number) => (checkPermi(['ls:dcs:view']) ? dcsApi.points({ assetId }) : Promise.resolve([] as DcsPoint[])),
  repairs: (defectId: number) => data<Record<string, any>[]>(request({ url: `/api/asset/defects/${defectId}/repairs`, method: 'get' }))
};

export type AssetApi = typeof assetApi;
