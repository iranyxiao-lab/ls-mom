import request from '@/utils/request';
import { crudApi } from './crud';
import type { OpResult } from './productionModel';
import type { AlarmContext, DcsAlarm, DcsPoint, Snapshot } from './dcsModel';

export * from './dcsModel';

const data = <T>(p: Promise<any>): Promise<T> => p.then((res) => res.data as T);
const key = () => (globalThis.crypto?.randomUUID ? globalThis.crypto.randomUUID() : String(Date.now() + Math.random()));

/** DCS 只读集成：测点、快照、报警处置。没有任何写入 DCS 的接口。 */
export const dcsApi = {
  points: (params: { assetId?: number; keyword?: string } = {}) => data<DcsPoint[]>(request({ url: '/api/dcs/points', method: 'get', params })),
  savePoint: (p: Partial<DcsPoint>) =>
    data<DcsPoint>(
      request({
        url: '/api/dcs/points',
        method: 'put',
        data: {
          code: p.code,
          name: p.name,
          unit: p.unit,
          rangeLow: p.range_low ?? null,
          rangeHigh: p.range_high ?? null,
          sourceSystem: p.source_system,
          assetId: p.asset_id ?? null,
          orgUnitId: p.org_unit_id ?? null,
          processName: p.process_name,
          alarmPolicy: p.alarm_policy,
          ownerParty: p.owner_party ?? null,
          enabled: p.enabled ?? true
        }
      })
    ),
  snapshots: (code: string, from?: string, to?: string) =>
    data<Snapshot[]>(request({ url: `/api/dcs/points/${encodeURIComponent(code)}/snapshots`, method: 'get', params: { from, to } })),
  alarms: (params: { status?: string; assetId?: number; pointCode?: string } = {}) =>
    data<DcsAlarm[]>(request({ url: '/api/dcs/alarms', method: 'get', params })),
  alarm: (id: number) => data<DcsAlarm>(request({ url: `/api/dcs/alarms/${id}`, method: 'get' })),
  context: (id: number, beforeMinutes = 30, afterMinutes = 30) =>
    data<AlarmContext>(request({ url: `/api/dcs/alarms/${id}/context`, method: 'get', params: { beforeMinutes, afterMinutes } })),
  link: (id: number, issueId?: number) => data<DcsAlarm>(request({ url: `/api/dcs/alarms/${id}/link`, method: 'post', data: { issueId } })),
  draft: (id: number) => data<DcsAlarm>(request({ url: `/api/dcs/alarms/${id}/draft`, method: 'post' })),
  ignore: (id: number) => data<DcsAlarm>(request({ url: `/api/dcs/alarms/${id}/ignore`, method: 'post' })),
  /** 人工确认 DCS 生成的问题草稿：草稿 → 正式问题。 */
  confirmIssue: (issueId: number, version: number) =>
    crudApi.execute('ls.core.Issue.submit', { targetId: String(issueId), version, input: {} }, key()) as Promise<OpResult>,
  assets: (keyword?: string) =>
    crudApi
      .query('ls.asset.Asset', {
        page: 1,
        size: 50,
        filters: keyword ? [{ field: 'assetCode', op: 'like', value: keyword }] : [],
        sort: [{ field: 'assetCode', dir: 'asc' }]
      })
      .then((p: any) => (p.rows ?? []) as Record<string, any>[])
};

export type DcsApi = typeof dcsApi;
