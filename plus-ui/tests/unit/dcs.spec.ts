/**
 * 15.2 / 15.4：DCS 只读集成——设备详情显示测点与最近快照；报警处置（建议关联、生成草稿、确认、忽略）；测点映射维护；快照趋势只画快照。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import AssetCenter from '@/views/lsmom/asset/center/AssetCenter.vue';
import AlarmBoard from '@/views/lsmom/dcs/alarms/AlarmBoard.vue';
import PointList from '@/views/lsmom/dcs/points/PointList.vue';
import TrendChart from '@/views/lsmom/dcs/TrendChart.vue';

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper, .el-message').forEach((e) => e.remove());
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}

const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

async function mount(comp: Component, api: any, props: Record<string, any> = {}) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(comp, { ref: r, api, ...props }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return r;
}

async function type(id: string, text: string) {
  const host = q(id)!;
  const el = (['INPUT', 'TEXTAREA'].includes(host.tagName) ? host : host.querySelector('input, textarea')) as HTMLInputElement;
  el.value = text;
  el.dispatchEvent(new Event('input'));
  el.dispatchEvent(new Event('change'));
  await flush(30);
}

const snaps = [0, 1, 2, 3].map((i) => ({
  bucket_start: `2026-11-10T08:0${i}:00Z`,
  bucket_seconds: 60,
  avg_value: 3 + i,
  min_value: 2.5 + i,
  max_value: 3.5 + i,
  last_value: 3 + i,
  sample_count: 60,
  bad_count: 0,
  skew_count: 0
}));

const alarm = (over: Record<string, any> = {}) => ({
  id: 41,
  dedup_key: 'DCS-A:P101.VIB:HI',
  source_system: 'DCS-A',
  point_code: 'P101.VIB',
  point_name: 'P-101 振动',
  unit: 'mm/s',
  alarm_type: 'HI',
  severity: 'MEDIUM',
  message: 'P-101 振动越上限 4.5mm/s',
  first_occurred_at: '2026-11-10T08:02:00Z',
  last_occurred_at: '2026-11-10T08:02:00Z',
  occurrence_count: 2,
  last_value: 4.8,
  clock_anomaly: false,
  status: 'SUGGESTED',
  suggested_issue_id: 9,
  suggested_issue_code: 'IS2026111000009',
  suggested_issue_title: 'P-101 泵体振动偏大',
  ...over
});

function alarmApi(a: Record<string, any>, over: Record<string, any> = {}) {
  return {
    alarms: vi.fn(async () => [a]),
    alarm: vi.fn(async () => a),
    context: vi.fn(async () => ({
      alarm: a,
      window: { from: '2026-11-10T07:32:00Z', to: '2026-11-10T08:32:00Z', bucketSeconds: 60 },
      snapshots: snaps,
      events: [
        {
          source_system: 'DCS-A',
          source_event_id: 'DCS-A:P101.VIB:HI:1',
          occurred_at: a.last_occurred_at,
          received_at: a.last_occurred_at,
          quality: 'GOOD',
          clock_anomaly: false,
          trace_id: 'sim-1'
        }
      ],
      rawRef: { sourceSystem: 'DCS-A', pointCode: 'P101.VIB', from: '', to: '', note: '' }
    })),
    link: vi.fn(async () => ({ ...a, status: 'LINKED', issue_id: 9, issue_code: 'IS2026111000009' })),
    draft: vi.fn(async () => ({ ...a, status: 'DRAFTED', issue_id: 12, issue_code: 'IS2026111000012' })),
    ignore: vi.fn(async () => ({ ...a, status: 'IGNORED' })),
    confirmIssue: vi.fn(async () => ({ targetId: '12', version: 2, status: 'OPEN', warnings: [] })),
    ...over
  };
}

describe('快照趋势', () => {
  it('画均值折线与区间，标出报警时间；无快照时提示', async () => {
    await mount(TrendChart, {}, { snapshots: snaps, mark: '2026-11-10T08:02:00Z' });
    expect(q('trend-line')!.getAttribute('d')!.split(/[ML]/).filter(Boolean)).toHaveLength(4);
    expect(q('trend-mark')).not.toBeNull();
    app!.unmount();
    host!.remove();
    await mount(TrendChart, {}, { snapshots: [] });
    expect(q('trend-chart')!.textContent).toContain('暂无快照');
  });
});

describe('设备详情的测点', () => {
  it('显示映射到该设备的测点与最近快照值', async () => {
    const pump = { id: 3, version: 1, assetCode: 'P-101', name: '循环泵', assetType: 'PUMP', status: 'RUNNING', orgId: 7 };
    const api = {
      timeline: vi.fn(async () => []),
      orgs: vi.fn(async () => []),
      parties: vi.fn(async () => []),
      query: vi.fn(async () => ({ rows: [pump], total: 1, page: 1, size: 100 })),
      get: vi.fn(async () => pump),
      points: vi.fn(async () => [
        { code: 'P101.VIB', name: 'P-101 振动', unit: 'mm/s', last_bucket: '2026-11-10T08:03:00', last_avg: 3.2149, last_max: 4.1, open_alarms: 1 }
      ])
    };
    await mount(AssetCenter, api);
    q('asset-row-3')!.click();
    await flush(200);
    expect(api.points).toHaveBeenCalledWith(3);
    expect(q('asset-points')!.textContent).toContain('3.215 mm/s');
    expect(q('asset-point-P101.VIB')!.textContent).toContain('P-101 振动');
  });
});

describe('DCS 报警处置', () => {
  it('先人工后报警：建议关联到已有问题，确认后挂在该问题下', async () => {
    const api = alarmApi(alarm());
    const board = await mount(AlarmBoard, api);
    expect(api.alarms).toHaveBeenCalledWith({ status: 'NEW,DRAFTED,NOTIFIED,SUGGESTED' });
    expect(q('alarm-row-P101.VIB')).not.toBeNull();
    await board.value.open(41);
    await flush(200);
    expect(q('alarm-suggestion')!.textContent).toContain('P-101 泵体振动偏大');
    expect(q('alarm-window')!.textContent).toContain('4 个 60 秒快照');
    expect(q('alarm-events')!.textContent).toContain('DCS-A:P101.VIB:HI:1');
    api.alarm.mockResolvedValue(
      alarm({ status: 'LINKED', issue_id: 9, issue_code: 'IS2026111000009', issue_title: 'P-101 泵体振动偏大', issue_status: 'OPEN' })
    );
    q('btn-alarm-link')!.click();
    await flush(200);
    expect(api.link).toHaveBeenCalledWith(41, 9);
    expect(q('alarm-detail-status')!.textContent).toContain('已关联问题');
    expect(q('btn-alarm-confirm')).toBeNull();
  });

  it('自动草稿：确认为正式问题；仅提醒的报警可生成草稿或忽略', async () => {
    const drafted = alarm({
      status: 'DRAFTED',
      suggested_issue_id: null,
      issue_id: 12,
      issue_code: 'IS2026111000012',
      issue_status: 'DRAFT',
      issue_version: 1
    });
    const api = alarmApi(drafted);
    const board = await mount(AlarmBoard, api);
    await board.value.open(41);
    await flush(200);
    expect(q('alarm-issue')!.textContent).toContain('草稿待确认');
    q('btn-alarm-confirm')!.click();
    await flush(200);
    expect(api.confirmIssue).toHaveBeenCalledWith(12, 1);

    const notified = alarm({ status: 'NOTIFIED', suggested_issue_id: null });
    const api2 = alarmApi(notified);
    app!.unmount();
    host!.remove();
    const b2 = await mount(AlarmBoard, api2);
    await b2.value.open(41);
    await flush(200);
    q('btn-alarm-ignore')!.click();
    await flush(200);
    expect(api2.ignore).toHaveBeenCalledWith(41);
  });

  it('无处置权限只读', async () => {
    const api = alarmApi(alarm({ status: 'NOTIFIED', suggested_issue_id: null }));
    const board = await mount(AlarmBoard, api, { canHandle: false });
    await board.value.open(41);
    await flush(200);
    expect(q('btn-alarm-draft')).toBeNull();
    expect(q('btn-alarm-ignore')).toBeNull();
  });
});

describe('测点维护', () => {
  it('打开测点看快照趋势，改处置策略后保存', async () => {
    const point = {
      id: 1,
      code: 'P101.TEMP',
      name: 'P-101 轴承温度',
      unit: '℃',
      source_system: 'DCS-A',
      alarm_policy: 'AUTO_DRAFT',
      enabled: true,
      asset_id: 3
    };
    const api = {
      points: vi.fn(async () => [point]),
      savePoint: vi.fn(async (p: any) => p),
      snapshots: vi.fn(async () => snaps),
      assets: vi.fn(async () => [{ id: 3, assetCode: 'P-101', name: '循环泵' }])
    };
    await mount(PointList, api, { canManage: true });
    expect(q('point-table')!.textContent).toContain('P-101 循环泵');
    q('point-row-P101.TEMP')!.click();
    await flush(200);
    expect(api.snapshots).toHaveBeenCalledWith('P101.TEMP');
    expect(q('trend-line')).not.toBeNull();
    (q('point-policy-NOTIFY')!.querySelector('input') as HTMLInputElement).click();
    await flush();
    q('btn-point-save')!.click();
    await flush(200);
    expect(api.savePoint).toHaveBeenCalledWith(expect.objectContaining({ code: 'P101.TEMP', alarm_policy: 'NOTIFY', asset_id: 3 }));
  });
});
