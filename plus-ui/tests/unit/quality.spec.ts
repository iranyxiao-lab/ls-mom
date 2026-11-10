/**
 * 14.2：质量中心——质量记录多对象可选关联、不符合项高严重度条件必填（与后端规则一致）、车间审核只提交审核意见与立即措施、终判。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import QualityRecords from '@/views/lsmom/quality/records/QualityRecords.vue';
import NcBoard from '@/views/lsmom/quality/nc/NcBoard.vue';
import { ncMissing } from '@/api/lsmom/qualityModel';

function makeApi(overrides: Record<string, any> = {}) {
  return {
    objects: vi.fn(async (type: string) => (type === 'ASSET' ? [{ id: 3, label: 'P-101 循环泵' }] : [{ id: 70, label: 'W70 焊接 A 段' }])),
    orgs: vi.fn(async () => [{ id: 7, name: '一车间', unit_type: 'WORKSHOP' }]),
    rectifications: vi.fn(async () => [] as any[]),
    impact: vi.fn(async (operation: string) => ({
      operation,
      name: '关闭',
      undoable: false,
      fromStatus: 'PENDING_VERIFY',
      toStatus: 'CLOSED',
      terminal: true,
      lines: ['将关闭 1 个对象：测试', '关联整改工作 1 项（已完成 1 项）']
    })),
    parties: vi.fn(async () => [{ id: 5, name: '郑师傅' }]),
    query: vi.fn(async () => ({ rows: [], total: 0, page: 1, size: 100 })),
    get: vi.fn(async () => ({})),
    create: vi.fn(async () => ({ id: 99, version: 0 })),
    op: vi.fn(async () => ({ targetId: '99', version: 1, status: 'OK', warnings: [] as string[], data: {} })),
    upload: vi.fn(async () => ({ ossId: '777', fileName: 'p.jpg', url: '' })),
    attach: vi.fn(async () => ({ targetId: '1', version: 1, status: 'OK', warnings: [] })),
    ...overrides
  };
}

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper, .el-message, .el-popper').forEach((e) => e.remove());
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}

const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

async function mount(comp: Component, api: any) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(comp, { ref: r, api }) });
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
  await flush(30);
}

describe('不符合项条件必填', () => {
  it('只有严重程度为高时要求相关设备与立即措施', () => {
    expect(ncMissing({ severity: 'MEDIUM' })).toEqual([]);
    expect(ncMissing({ severity: 'HIGH' })).toEqual(['asset', 'immediateAction']);
    expect(ncMissing({ severity: 'HIGH', asset: 3, immediateAction: ' ' })).toEqual(['immediateAction']);
    expect(ncMissing({ severity: 'HIGH', asset: 3, immediateAction: '隔离' })).toEqual([]);
  });
});

describe('质量记录', () => {
  it('不关联也能登记；关联工作与设备时一并提交关联子表并登记', async () => {
    const api = makeApi();
    const page = await mount(QualityRecords, api);
    page.value.openNew();
    await flush(150);
    await type('qr-title', '来料抽检');
    q('btn-save-record')!.click();
    await flush(150);
    expect(api.create).toHaveBeenLastCalledWith('ls.quality.QualityRecord', {
      title: '来料抽检',
      recordType: 'INSPECTION',
      result: 'PASS',
      description: '',
      qualityRecordLink: []
    });
    expect(api.op).toHaveBeenLastCalledWith('ls.quality.QualityRecord.submit', 99, 0);
  });
});

describe('不符合项', () => {
  it('高严重度缺设备与立即措施：前端拦下并标出两个字段，不调用后端', async () => {
    const api = makeApi();
    const board = await mount(NcBoard, api);
    await board.value.openNew();
    await flush(150);
    await type('nc-title', '焊缝气孔');
    await type('nc-description', '抽检发现焊缝气孔');
    q('nc-sev-HIGH')!.querySelector('input')!.click();
    await flush();
    q('btn-save-nc')!.click();
    await flush(150);
    expect(api.create).not.toHaveBeenCalled();
    expect(document.body.textContent).toContain('严重程度为高时必须填写相关设备');
    expect(document.body.textContent).toContain('严重程度为高时必须填写立即措施');
  });

  it('车间审核：问题描述只读，只提交审核意见、立即措施与派工', async () => {
    const nc = { id: 41, version: 2, status: 'PENDING_AUDIT', title: '尺寸超差', description: '轴径 50.08', severity: 'MEDIUM' };
    const api = makeApi({ get: vi.fn(async () => nc) });
    const board = await mount(NcBoard, api);
    await board.value.openDetail(41);
    await flush(200);
    expect((q('nc-detail-description') as HTMLTextAreaElement).readOnly).toBe(true);
    await type('nc-audit-opinion', '同意整改');
    await type('nc-audit-action', '隔离 3 件');
    q('nc-owner')!.click();
    await flush(150);
    (document.querySelector('.el-select-dropdown__item') as HTMLElement).click();
    await flush();
    q('btn-nc-audit')!.click();
    await flush(150);
    expect(api.op).toHaveBeenCalledWith('ls.quality.NonConformance.audit', 41, 2, {
      auditOpinion: '同意整改',
      owner: 5,
      immediateAction: '隔离 3 件'
    });
  });

  it('待验证：填结论后终判关闭', async () => {
    const nc = { id: 41, version: 5, status: 'PENDING_VERIFY', title: '尺寸超差', description: '轴径 50.08', severity: 'MEDIUM' };
    const api = makeApi({
      get: vi.fn(async () => nc),
      rectifications: vi.fn(async () => [{ id: 70, code: 'W70', title: '调整刀补', status: 'DONE', version: 4, evidence: 1, assignees: '郑师傅' }])
    });
    const board = await mount(NcBoard, api);
    await board.value.openDetail(41);
    await flush(200);
    expect(q('nc-work-evidence-70')!.textContent).toContain('1');
    await type('nc-conclusion', '复检合格');
    q('btn-nc-close')!.click();
    await flush(300);
    // 关闭不可撤销：确认框列出影响、说明关闭后不能再编辑
    const body = document.querySelector('[data-testid="op-confirm-body"]')!.textContent!;
    expect(body).toContain('关联整改工作 1 项');
    expect(body).toContain('状态：待验证 → 已关闭');
    expect(body).toContain('流程结束，不能再编辑或办理');
    expect(api.impact).toHaveBeenCalledWith('ls.quality.NonConformance.close', 41);
    expect(api.op).not.toHaveBeenCalledWith('ls.quality.NonConformance.close', expect.anything(), expect.anything(), expect.anything());
    (document.querySelector('.ls-op-confirm-box .el-button--primary') as HTMLElement).click();
    await flush(300);
    expect(api.op).toHaveBeenCalledWith('ls.quality.NonConformance.close', 41, 5, { conclusion: '复检合格' });
  });
});
