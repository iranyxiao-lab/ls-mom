/**
 * 14.1：设备中心——状态变更带原因、时间轴、点检逐项填写与超限判异常、一键生成缺陷草稿、缺陷维修与验证。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import AssetCenter from '@/views/lsmom/asset/center/AssetCenter.vue';
import InspectionDesk from '@/views/lsmom/asset/inspection/InspectionDesk.vue';
import DefectBoard from '@/views/lsmom/asset/defects/DefectBoard.vue';
import { outOfRange } from '@/api/lsmom/assetModel';

function makeApi(overrides: Record<string, any> = {}) {
  return {
    timeline: vi.fn(async () => [] as any[]),
    impact: vi.fn(async (operation: string) => ({
      operation,
      name: '关闭',
      undoable: false,
      fromStatus: 'PENDING_VERIFY',
      toStatus: 'CLOSED',
      terminal: true,
      lines: ['将关闭 1 个对象：测试', '关联整改工作 1 项（已完成 1 项）']
    })),
    myInspections: vi.fn(async () => [] as any[]),
    orgs: vi.fn(async () => [{ id: 7, name: '一车间', unit_type: 'WORKSHOP' }]),
    parties: vi.fn(async () => [{ id: 5, name: '郑师傅' }]),
    query: vi.fn(async () => ({ rows: [], total: 0, page: 1, size: 100 })),
    get: vi.fn(async () => ({})),
    create: vi.fn(async () => ({ id: 99, version: 0 })),
    op: vi.fn(async () => ({ targetId: '1', version: 1, status: 'OK', warnings: [] as string[], data: {} })),
    upload: vi.fn(async () => ({ ossId: '777', fileName: 'p.jpg', url: '' })),
    attach: vi.fn(async () => ({ targetId: '1', version: 1, status: 'OK', warnings: [] })),
    repairs: vi.fn(async () => [] as any[]),
    ...overrides
  };
}

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

describe('超限判定', () => {
  it('高于上限或低于下限为异常，空值不判', () => {
    expect(outOfRange(82, null, 70)).toBe(true);
    expect(outOfRange(65, null, 70)).toBe(false);
    expect(outOfRange(1, 2, null)).toBe(true);
    expect(outOfRange(undefined, 0, 70)).toBe(false);
  });
});

describe('设备台账', () => {
  it('运行中的泵可停机检修：必须填原因，提交 maintain；时间轴显示状态与缺陷', async () => {
    const pump = { id: 3, version: 1, assetCode: 'P-101', name: '循环泵', assetType: 'PUMP', status: 'RUNNING', orgId: 7 };
    const api = makeApi({
      query: vi.fn(async () => ({ rows: [pump], total: 1, page: 1, size: 100 })),
      get: vi.fn(async () => pump),
      timeline: vi.fn(async () => [
        { kind: 'DEFECT', at: '2026-11-10T09:00:00', title: '登记缺陷：机封泄漏', concept: 'ls.asset.Defect', id: 1 },
        { kind: 'STATUS', at: '2026-11-09T08:00:00', title: '状态：DRAFT → RUNNING', concept: 'ls.asset.AssetStatusLog', id: 2 }
      ])
    });
    await mount(AssetCenter, api);
    expect(q('asset-row-3')!.textContent).toContain('P-101');
    q('asset-row-3')!.click();
    await flush(200);
    expect(q('asset-timeline')!.textContent).toContain('登记缺陷：机封泄漏');
    expect(q('btn-asset-commission')).toBeNull();
    q('btn-asset-maintain')!.click();
    await flush(150);
    q('btn-asset-op-confirm')!.click();
    await flush();
    expect(api.op).not.toHaveBeenCalled();
    await type('asset-reason', '机封泄漏');
    q('btn-asset-op-confirm')!.click();
    await flush(150);
    expect(api.op).toHaveBeenCalledWith('ls.asset.Asset.maintain', 3, 1, { reason: '机封泄漏' });
  });
});

describe('点检', () => {
  const rec = {
    id: 8,
    version: 1,
    status: 'DRAFT',
    asset: { id: 3, label: 'P-101' },
    dueDate: '2026-11-10',
    inspectionRecordItem: [
      { itemCode: 'TEMP', name: '轴承温度', valueType: 'NUMBER', unit: '℃', upperLimit: 70, standard: '≤70℃' },
      { itemCode: 'LEAK', name: '机封泄漏', valueType: 'CHECK', standard: '无滴漏' }
    ]
  };

  it('逐项填写后提交 submit；异常提示后可一键生成缺陷草稿', async () => {
    const api = makeApi({
      myInspections: vi.fn(async () => [
        { id: 8, version: 1, status: 'DRAFT', due_date: '2026-11-10', asset_id: 3, asset_code: 'P-101', asset_name: '循环泵' }
      ]),
      get: vi.fn(async () => rec),
      op: vi.fn(async () => ({
        targetId: '8',
        version: 2,
        status: 'SUBMITTED',
        warnings: ['有 1 项点检异常：轴承温度，可一键生成缺陷草稿'],
        data: {}
      }))
    });
    const desk = await mount(InspectionDesk, api);
    expect(q('ins-8')!.textContent).toContain('P-101 循环泵');
    await desk.value.openRecord(8);
    await flush(200);
    await type('ins-value-TEMP', '82');
    q('ins-ok-LEAK')!.querySelector('input')!.click();
    await flush();
    q('btn-ins-submit')!.click();
    await flush(200);
    expect(api.op).toHaveBeenCalledWith('ls.asset.InspectionRecord.submit', 8, 1, {
      items: [
        { itemCode: 'TEMP', valueNum: 82, outcome: null, note: null },
        { itemCode: 'LEAK', valueNum: null, outcome: 'OK', note: null }
      ]
    });
    expect(q('ins-abnormal-hint')!.textContent).toContain('轴承温度');

    api.get.mockImplementation(async () => ({
      ...rec,
      status: 'SUBMITTED',
      version: 2,
      inspectionRecordItem: [
        { ...rec.inspectionRecordItem[0], valueNum: 82, outcome: 'ABNORMAL' },
        { ...rec.inspectionRecordItem[1], outcome: 'OK' }
      ]
    }));
    api.op.mockImplementation(async () => ({ targetId: '8', version: 3, status: 'SUBMITTED', warnings: [], data: { defects: [41] } }));
    await desk.value.openRecord(8);
    await flush(200);
    expect(q('ins-outcome-TEMP')!.textContent).toContain('异常');
    q('btn-raise-defects')!.click();
    await flush(150);
    expect(api.op).toHaveBeenLastCalledWith('ls.asset.InspectionRecord.raiseDefects', 8, 2);
  });
});

describe('缺陷与维修', () => {
  const defect = (status: string) => ({
    id: 41,
    version: 3,
    status,
    title: '轴承温度异常',
    severity: 'MEDIUM',
    asset: { id: 3, label: 'P-101' },
    issue: 9
  });

  it('待处理：派维修人并附检查清单生成维修工作；待验证：填结论验证通过', async () => {
    const api = makeApi({
      query: vi.fn(async () => ({ rows: [defect('REPORTED')], total: 1, page: 1, size: 100 })),
      get: vi.fn(async () => defect('REPORTED'))
    });
    const board = await mount(DefectBoard, api);
    expect(q('df-row-41')!.textContent).toContain('轴承温度异常');
    await board.value.openDetail(41);
    await flush(200);
    (document.querySelector('[data-testid="df-checklist"]') as HTMLTextAreaElement).value = '更换轴承\n复测温度';
    q('df-checklist')!.dispatchEvent(new Event('input'));
    await flush(30);
    q('btn-df-repair')!.click();
    await flush(150);
    expect(api.op).toHaveBeenCalledWith('ls.asset.Defect.createRepair', 41, 3, { checklist: ['更换轴承', '复测温度'] });

    api.get.mockImplementation(async () => defect('PENDING_VERIFY'));
    api.repairs.mockImplementation(async () => [
      { id: 70, code: 'W70', title: '维修：轴承温度异常', status: 'DONE', version: 4, assignees: '郑师傅' }
    ]);
    await board.value.openDetail(41);
    await flush(200);
    expect(q('df-works')!.textContent).toContain('W70');
    q('btn-df-pass')!.click();
    await flush();
    expect(api.op).toHaveBeenCalledTimes(1);
    await type('df-conclusion', '温度恢复 55℃');
    q('btn-df-pass')!.click();
    await flush(300);
    expect(document.querySelector('[data-testid="op-confirm-body"]')!.textContent).toContain('此操作不能撤销');
    (document.querySelector('.ls-op-confirm-box .el-button--primary') as HTMLElement).click();
    await flush(300);
    expect(api.op).toHaveBeenLastCalledWith('ls.asset.Defect.verifyPass', 41, 3, { conclusion: '温度恢复 55℃' });
  });
});

describe('设备台账搜索', () => {
  it('输入框同时按设备编码与名称搜索，结果合并去重并按编码排序', async () => {
    const rows = [
      { id: 1, assetCode: 'P-101', name: '循环泵', status: 'RUNNING' },
      { id: 2, assetCode: 'TL-9', name: 'P-101 备用泵', status: 'RUNNING' }
    ];
    const api = makeApi({
      query: vi.fn(async (_c: string, req: any) => {
        const kw = req.filters.find((f: any) => f.op === 'like');
        if (!kw) return { rows, total: 2, page: 1, size: 200 };
        const hit = rows.filter((r: any) => String(r[kw.field]).includes(kw.value));
        return { rows: hit, total: hit.length, page: 1, size: 200 };
      })
    });
    await mount(AssetCenter, api);
    await type('asset-keyword', 'P-101');
    (document.querySelector('[data-testid="asset-keyword"] input, input[data-testid="asset-keyword"]') as HTMLInputElement).dispatchEvent(
      new Event('change')
    );
    await flush(120);
    const fields = api.query.mock.calls.slice(-2).map((c: any[]) => c[1].filters.find((f: any) => f.op === 'like')?.field);
    expect(fields.sort()).toEqual(['assetCode', 'name']);
    const codes = [...document.querySelectorAll('[data-testid^="asset-row-"]')].map((e) => e.textContent?.trim());
    expect(codes).toEqual(['P-101', 'TL-9']);
  });
});
