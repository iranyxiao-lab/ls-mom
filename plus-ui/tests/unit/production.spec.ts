/**
 * 13.3：轻生产运营工作台——需求板拆分转工作、工作板拖动调班、移动填报拍照证据、交接班按状态操作。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import RequestBoard from '@/views/lsmom/production/requests/RequestBoard.vue';
import WorkBoard from '@/views/lsmom/production/board/WorkBoard.vue';
import MobileReport from '@/views/lsmom/production/report/MobileReport.vue';
import HandoverPage from '@/views/lsmom/production/handover/HandoverPage.vue';
import { suggestWorks, type BoardWork } from '@/api/lsmom/productionModel';

const work = (id: number, extra: Partial<BoardWork> = {}): BoardWork => ({
  id,
  code: `W${id}`,
  title: `工作${id}`,
  status: 'ASSIGNED',
  priority: 3,
  orgUnitId: 18,
  version: 2,
  risks: [],
  assignees: [{ type: 'PARTY', ref: '5', name: '郑师傅' }],
  ...extra
});

function makeApi(overrides: Record<string, any> = {}) {
  return {
    board: vi.fn(async () => [] as BoardWork[]),
    teams: vi.fn(async () => [
      { id: 18, name: '甲班', parentName: '一车间' },
      { id: 12, name: '乙班', parentName: '一车间' }
    ]),
    myTeams: vi.fn(async () => [{ id: 18, name: '甲班' }]),
    parties: vi.fn(async () => []),
    query: vi.fn(async () => ({ rows: [], total: 0, page: 1, size: 100 })),
    get: vi.fn(async () => ({})),
    create: vi.fn(async () => ({ id: 99, version: 0 })),
    update: vi.fn(async () => ({ id: 99, version: 1 })),
    op: vi.fn(async () => ({ targetId: '1', version: 1, status: 'OK', warnings: [] as string[], data: {} })),
    upload: vi.fn(async () => ({ ossId: '777', fileName: 'p.jpg', url: '' })),
    attach: vi.fn(async () => ({ targetId: '1', version: 1, status: 'OK', warnings: [] })),
    docs: vi.fn(async () => [] as Record<string, any>[]),
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

describe('需求拆分建议', () => {
  it('"周三前完成 A 线清洗与 200 件组装"拆成两条，第二条带数量单位', () => {
    expect(suggestWorks('周三前完成 A 线清洗与 200 件组装')).toEqual([{ title: 'A 线清洗' }, { title: '200 件组装', quantity: 200, unit: '件' }]);
    expect(suggestWorks('临时：仓库盘点')).toEqual([{ title: '临时：仓库盘点' }]);
  });
});

describe('生产需求板', () => {
  it('转为工作：按描述预填两条，可指定班组班次，提交 convert Operation', async () => {
    const api = makeApi({
      query: vi.fn(async () => ({
        rows: [{ id: 3, version: 1, title: '周三前完成 A 线清洗与 200 件组装', period: 'WEEK', status: 'OPEN', workCount: 0 }],
        total: 1,
        page: 1,
        size: 100
      }))
    });
    await mount(RequestBoard, api);
    q('btn-convert-3')!.click();
    await flush(150);
    expect((q('convert-title-0') as HTMLInputElement).value).toBe('A 线清洗');
    expect((q('convert-title-1') as HTMLInputElement).value).toBe('200 件组装');
    q('btn-do-convert')!.click();
    await flush(150);
    expect(api.op).toHaveBeenCalledWith('ls.production.ProductionRequest.convert', 3, 1, {
      works: [{ title: 'A 线清洗' }, { title: '200 件组装', quantity: 200, unit: '件' }]
    });
  });
});

describe('工作板', () => {
  it('班次看板按班次分列；拖到中班调用 move，冲突提示显示出来', async () => {
    const rows = [
      work(1, { shift: 'DAY', shiftDate: '2026-11-10', teamId: 18, teamName: '甲班', planId: 41, planVersion: 1 }),
      work(2, { shift: 'EVENING', shiftDate: '2026-11-10', teamId: 18, teamName: '甲班', planId: 42, planVersion: 3 }),
      work(3, { risks: ['UNSCHEDULED', 'OVERDUE'] })
    ];
    const api = makeApi({
      board: vi.fn(async () => rows),
      op: vi.fn(async () => ({ targetId: '41', version: 2, status: 'ACTIVE', warnings: ['资源时间冲突：W2 工作2'] }))
    });
    const board = await mount(WorkBoard, api, { initialDay: '2026-11-10' });
    expect(q('col-DAY')!.textContent).toContain('工作1');
    expect(q('col-EVENING')!.textContent).toContain('工作2');
    expect(q('col-NONE')!.textContent).toContain('工作3');
    expect(q('card-3')!.textContent).toContain('已逾期');
    expect(api.board).toHaveBeenCalledWith(expect.objectContaining({ from: '2026-11-10', to: '2026-11-10' }));

    board.value.dragging = rows[0];
    await board.value.onDrop('EVENING');
    await flush();
    expect(api.op).toHaveBeenCalledWith('ls.production.ShiftPlan.move', 41, 1, { shift: 'EVENING', shiftDate: '2026-11-10' });
    expect(document.body.textContent).toContain('资源时间冲突');
  });

  it('拖动调整班次先在看板上移过去；5 秒内点撤销，以补偿 Operation 恢复原班次', async () => {
    const rows = [work(1, { shift: 'DAY', shiftDate: '2026-11-10', teamId: 18, teamName: '甲班', planId: 41, planVersion: 1 })];
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let calls = 0;
    const api = makeApi({
      board: vi.fn(async () => rows.map((r) => ({ ...r }))),
      op: vi.fn(async (code: string) => {
        if (++calls === 1) await gate;
        return {
          targetId: '41',
          version: 2,
          status: 'ACTIVE',
          warnings: [],
          undo: { operation: 'ls.production.ShiftPlan.move', targetId: '41', input: { teamId: 18, shiftDate: '2026-11-10', shift: 'DAY' } }
        };
      })
    });
    const board = await mount(WorkBoard, api, { initialDay: '2026-11-10' });
    board.value.dragging = board.value.rows[0];
    const p = board.value.onDrop('EVENING');
    await flush();
    // 乐观更新：服务端还没返回，卡片已在中班
    expect(q('col-EVENING')!.textContent).toContain('工作1');
    release();
    await p;
    await flush();
    const undo = document.querySelector('[data-testid="undo-btn"]') as HTMLElement;
    expect(undo.closest('.el-message')!.textContent).toContain('已调整到中班');
    undo.click();
    await flush();
    expect(api.op).toHaveBeenLastCalledWith('ls.production.ShiftPlan.move', '41', 2, { teamId: 18, shiftDate: '2026-11-10', shift: 'DAY' });
    expect(document.body.textContent).toContain('已撤销');
  });

  it('未排班的工作拖入班次：新建班次安排并生效', async () => {
    const rows = [work(3, { risks: ['UNSCHEDULED'] })];
    const api = makeApi({ board: vi.fn(async () => rows) });
    const board = await mount(WorkBoard, api, { initialDay: '2026-11-10' });
    board.value.dragging = rows[0];
    await board.value.onDrop('NIGHT');
    expect(api.create).toHaveBeenCalledWith('ls.production.ShiftPlan', { workItem: 3, team: 18, shiftDate: '2026-11-10', shift: 'NIGHT' });
    expect(api.op).toHaveBeenCalledWith('ls.production.ShiftPlan.activate', 99, 0);
  });
});

describe('移动填报', () => {
  it('填进度并拍照提交：照片上传后作为证据挂到本次填报', async () => {
    const api = makeApi({
      board: vi.fn(async () => [work(5, { status: 'IN_PROGRESS', progress: 40, unit: '件' })]),
      op: vi.fn(async () => ({ targetId: '5', version: 3, status: 'IN_PROGRESS', warnings: [], data: { reportId: 31, status: 'SUBMITTED' } })),
      docs: vi.fn(async () => [{ id: 1, fileName: 'p.jpg' }])
    });
    await mount(MobileReport, api);
    expect(api.board).toHaveBeenCalledWith({ mine: true });
    q('m-work-5')!.click();
    await flush();
    const input = q('m-photo') as HTMLInputElement;
    const file = new File([new Uint8Array([1, 2, 3])], 'p.jpg', { type: 'image/jpeg' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await flush();
    q('m-submit')!.click();
    await flush(150);
    expect(api.op).toHaveBeenCalledWith(
      'ls.core.Work.submitReport',
      5,
      undefined,
      expect.objectContaining({ progress: 40, laborMinutes: 0, outputUnit: '件' })
    );
    expect(api.upload).toHaveBeenCalledWith(file);
    expect(api.attach).toHaveBeenCalledWith('777', 'ls.core.WorkReport', 31, 'EVIDENCE');
    expect(q('m-evidence')!.textContent).toContain('p.jpg');
  });
});

describe('交接班', () => {
  const ho = (status: string) => ({
    id: 8,
    version: 2,
    status,
    fromTeam: { id: 18, label: '甲班' },
    toTeam: { id: 12, label: '乙班' },
    shiftDate: '2026-11-10',
    shift: 'DAY',
    summary: '甲班 2026-11-10 早班：完成 1 项，未完成 1 项。',
    shiftHandoverItem: [{ kind: 'UNFINISHED', title: 'W2 组装', workStatus: 'IN_PROGRESS' }]
  });

  it('草稿：可改摘要并确认交班；待接班：显示接班与退回', async () => {
    const api = makeApi({
      query: vi.fn(async () => ({ rows: [ho('DRAFT')], total: 1, page: 1, size: 100 })),
      get: vi.fn(async () => ho('DRAFT'))
    });
    await mount(HandoverPage, api);
    expect(q('ho-row-8')!.textContent).toContain('甲班 → 乙班');
    q('ho-row-8')!.click();
    await flush(200);
    expect(q('ho-items')!.textContent).toContain('W2 组装');
    expect(q('btn-accept')).toBeNull();
    q('btn-publish')!.click();
    await flush(150);
    expect(api.op).toHaveBeenCalledWith('ls.production.ShiftHandover.publish', 8, 2, { summary: '甲班 2026-11-10 早班：完成 1 项，未完成 1 项。' });

    api.get.mockImplementation(async () => ho('PUBLISHED'));
    q('ho-row-8')!.click();
    await flush(200);
    expect(q('btn-publish')).toBeNull();
    q('btn-accept')!.click();
    await flush(150);
    expect(api.op).toHaveBeenCalledWith('ls.production.ShiftHandover.accept', 8, 2, {});
  });
});
