/**
 * 14.3：通用台账工作区——默认表单录入并经 Operation 提交、旧单按冻结表单版本只读详情、导入预检有错误行时不能确认。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import { createMemoryHistory, createRouter } from 'vue-router';
import 'element-plus/dist/index.css';
import LedgerWorkspace from '@/views/lsmom/ledger/LedgerWorkspace.vue';
import type { FormSchema } from '@/types/lsView';

const C = 'acme.toolloan.LoanOrder';
const formV = (version: number, layout: FormSchema['layout']): FormSchema => ({
  viewType: 'FORM',
  formCode: 'acme.toolloan.loanForm',
  version,
  conceptCode: C,
  layout,
  fields: [
    { key: 'loanNo', label: '借用单号', widget: 'input', required: true },
    { key: 'remark', label: '备注', widget: 'textarea' }
  ],
  actions: [{ key: 'submit', label: '提交审核', operation: C + '.submit' }]
});
const v1 = formV(1, [
  { type: 'field', key: 'loanNo' },
  { type: 'field', key: 'remark' }
]);
const v2 = formV(2, [
  { type: 'text', text: '借用前请核对工器具编号' },
  { type: 'field', key: 'loanNo' },
  { type: 'field', key: 'remark' }
]);
const view = (s: FormSchema) => ({
  code: s.formCode,
  version: s.version,
  concept: C,
  type: 'FORM',
  displayName: '借还单',
  status: 'PUBLISHED',
  schema: s
});

function apis(record: Record<string, any> = {}) {
  const crud = {
    schema: vi.fn(async () => ({
      concept: C,
      displayName: '借还单',
      version: 1,
      fields: [{ code: 'loanNo', label: '借用单号', type: 'STRING', required: true, queryable: true, sortable: true, writable: true }],
      children: [],
      operations: [],
      permissions: { list: true, query: true, add: true, edit: true, remove: false, export: true, import: true }
    })),
    query: vi.fn(async () => ({ rows: [], total: 0, page: 1, size: 20 })),
    get: vi.fn(async () => record),
    create: vi.fn(async () => ({ id: 41, version: 0 })),
    update: vi.fn(async () => ({ id: 41, version: 1 })),
    execute: vi.fn(async () => ({ targetId: '41', version: 1, status: 'OK', warnings: [] })),
    importPrecheck: vi.fn(async () => ({ batchId: 9, total: 3, valid: 2, invalid: 1, errors: [{ row: 3, field: 'loanNo', message: '必填' }] })),
    importConfirm: vi.fn(async () => 2),
    importTemplate: vi.fn(async () => new Blob()),
    exportJob: vi.fn(),
    exportStatus: vi.fn(),
    exportFile: vi.fn(),
    timeline: vi.fn(async () => [] as any[])
  };
  const forms = {
    defaultFor: vi.fn(async () => view(v2)),
    get: vi.fn(async (_c: string, v?: number) => view(v === 1 ? v1 : v2)),
    editVersion: vi.fn(async () => ({ recordVersion: 1, latestVersion: 2, strategy: 'KEEP', outdated: true, useVersion: 1, prompt: false })),
    refSearch: vi.fn(async () => []),
    upload: vi.fn(),
    dict: vi.fn(async () => [])
  };
  return { crud, forms };
}

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper, .el-message, .el-popper').forEach((e) => e.remove());
});

const flush = async (ms = 80) => {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
};
const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

async function mount(a: ReturnType<typeof apis>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(LedgerWorkspace, { ref: r, concept: C, crud: a.crud as any, forms: a.forms as any }) });
  app.use(ElementPlus);
  app.use(createRouter({ history: createMemoryHistory(), routes: [{ path: '/:p(.*)*', component: { render: () => null } }] }));
  app.mount(host);
  await flush();
  return r;
}

describe('通用台账工作区', () => {
  it('新增：按默认表单录入，提交审核先保存（带表单版本）再执行 Operation', async () => {
    const a = apis();
    const w = await mount(a);
    await w.value.openCreate();
    await flush(150);
    const input = document.querySelector('[data-testid="ledger-editor"] input') as HTMLInputElement;
    input.value = 'T-1';
    input.dispatchEvent(new Event('input'));
    await flush(30);
    q('btn-form-submit')!.click();
    await flush(150);
    expect(a.crud.create).toHaveBeenCalledWith(
      C,
      expect.objectContaining({ loanNo: 'T-1', _form: { code: 'acme.toolloan.loanForm', version: 2 } }),
      expect.any(String)
    );
    expect(a.crud.execute).toHaveBeenCalledWith(C + '.submit', { targetId: '41', version: 0, input: {} }, expect.any(String));
  });

  it('必填未填不保存', async () => {
    const a = apis();
    const w = await mount(a);
    await w.value.openCreate();
    await flush(150);
    q('btn-save-draft')!.click();
    await flush(100);
    expect(a.crud.create).not.toHaveBeenCalled();
  });

  it('详情：按 v1 保存的记录按 v1 布局只读显示并提示最新版本', async () => {
    const a = apis({ id: 41, version: 3, status: 'ARCHIVED', schemaVersion: 1, loanNo: 'T-1', remark: '外协' });
    const w = await mount(a);
    await w.value.openDetail(41);
    await flush(200);
    expect(a.forms.get).toHaveBeenCalledWith('acme.toolloan.loanForm', 1);
    expect(q('frozen-hint')!.textContent).toContain('v1');
    expect(q('detail-form-version')!.textContent).toContain('v1');
    expect(document.querySelector('[data-testid="ledger-detail"]')!.textContent).not.toContain('借用前请核对工器具编号');
  });

  it('编辑旧草稿：迁移策略为保持旧版时按 v1 编辑', async () => {
    const a = apis({ id: 41, version: 3, status: 'DRAFT', schemaVersion: 1, loanNo: 'T-1' });
    const w = await mount(a);
    await w.value.openEdit(41);
    await flush(200);
    expect(a.forms.editVersion).toHaveBeenCalledWith('acme.toolloan.loanForm', 1);
    expect(q('edit-version-hint')!.textContent).toContain('v1');
    q('btn-save-draft')!.click();
    await flush(150);
    expect(a.crud.update).toHaveBeenCalledWith(
      C,
      41,
      3,
      expect.objectContaining({ _form: { code: 'acme.toolloan.loanForm', version: 1 } }),
      expect.any(String)
    );
  });

  it('导入：预检有错误行时不能确认', async () => {
    const a = apis();
    const w = await mount(a);
    w.value.openImport();
    await flush(100);
    const file = q('import-file') as HTMLInputElement;
    Object.defineProperty(file, 'files', { value: [new File(['x'], 'a.xlsx')] });
    file.dispatchEvent(new Event('change'));
    await flush(100);
    expect(q('import-summary')!.textContent).toContain('错误 1 行');
    expect((q('import-confirm') as HTMLButtonElement).disabled).toBe(true);
  });

  it('抽屉详情：上一条/下一条切换不刷新列表、时间轴、带输入的动作、关闭后才刷新、宽度记住、在新页签打开', async () => {
    const rows = [41, 42, 43].map((id) => ({ id, version: 1, status: 'PENDING', loanNo: `T-${id}` }));
    const a = apis();
    a.crud.query = vi.fn(async () => ({ rows, total: 3, page: 1, size: 20 }));
    a.crud.get = vi.fn(async (_c: string, id: number) => ({ ...rows.find((r) => r.id === Number(id))!, schemaVersion: 2 }));
    a.crud.schema = vi.fn(async () => ({
      concept: C,
      displayName: '借还单',
      version: 1,
      fields: [
        { code: 'loanNo', label: '借用单号', type: 'STRING', required: true, queryable: true, sortable: true, writable: true },
        { code: 'auditOpinion', label: '审核意见', type: 'TEXT', required: false, queryable: false, sortable: false, writable: true }
      ],
      children: [],
      states: [
        { code: 'DRAFT', label: '草稿', initial: true, terminal: false },
        { code: 'PENDING', label: '待审核', initial: false, terminal: false },
        { code: 'REJECTED', label: '已驳回', initial: false, terminal: true }
      ],
      operations: [
        {
          operation: C + '.reject',
          name: '驳回',
          kind: 'TRANSITION',
          risk: 'C1',
          permitted: true,
          from: ['PENDING'],
          inputSchema: { type: 'object', required: ['auditOpinion'], properties: { auditOpinion: { type: 'string', maxLength: 200 } } }
        },
        {
          operation: C + '.assign',
          name: '派人',
          kind: 'TRANSITION',
          risk: 'C1',
          permitted: true,
          from: ['PENDING'],
          inputSchema: { type: 'object', required: ['owner'], properties: { owner: { type: 'integer' }, extra: { type: 'array' } } }
        },
        { operation: C + '.archive', name: '归档', kind: 'TRANSITION', risk: 'C1', permitted: true, from: ['DONE'] }
      ],
      permissions: { list: true, query: true, add: true, edit: true, remove: false, export: true, import: true }
    })) as any;
    a.crud.timeline = vi.fn(async () => [
      { at: '2026-11-01T08:00:00', type: 'ls.op', action: C + '.submit', actorName: '张三', fromStatus: 'DRAFT', toStatus: 'PENDING' }
    ]);
    try {
      localStorage.removeItem('ls:ledger-detail-width');
    } catch {
      /* ignore */
    }
    const w = await mount(a);
    await flush(150);
    const queries = (a.crud.query as any).mock.calls.length;

    w.value.openDetail(42);
    await flush(250);
    expect(q('detail-pos')!.textContent).toBe('2 / 3');
    expect(q('detail-title')!.textContent).toContain('T-42');
    expect(q('detail-status')!.textContent).toBe('待审核');
    // 只显示当前状态可办理、输入能在抽屉里填写的动作
    expect(q('detail-op-' + C + '.reject')).not.toBeNull();
    expect(q('detail-op-' + C + '.assign')).toBeNull();
    expect(q('detail-op-' + C + '.archive')).toBeNull();
    const tl = document.querySelector('[data-testid="ledger-detail"] .el-tabs__item:nth-child(2)') as HTMLElement;
    tl.click();
    await flush();
    expect(q('detail-timeline')!.textContent).toContain('张三');
    expect(q('detail-timeline')!.textContent).toContain('草稿 → 待审核');

    q('detail-next')!.click();
    await flush(200);
    expect(q('detail-pos')!.textContent).toBe('3 / 3');
    expect(q('detail-title')!.textContent).toContain('T-43');
    expect((q('detail-next') as HTMLButtonElement).disabled).toBe(true);
    q('detail-prev')!.click();
    q('detail-prev')!.click();
    await flush(200);
    expect(q('detail-pos')!.textContent).toBe('1 / 3');
    expect((q('detail-prev') as HTMLButtonElement).disabled).toBe(true);
    expect((a.crud.query as any).mock.calls.length).toBe(queries);

    // 驳回需要填写审核意见
    q('detail-op-' + C + '.reject')!.click();
    await flush();
    q('detail-op-confirm')!.click();
    await flush();
    expect(a.crud.execute).not.toHaveBeenCalled();
    const tx = q('detail-in-auditOpinion')!;
    const ta = (tx.tagName === 'TEXTAREA' ? tx : tx.querySelector('textarea')) as HTMLTextAreaElement;
    ta.value = '数量不符';
    ta.dispatchEvent(new Event('input'));
    await flush();
    q('detail-op-confirm')!.click();
    await flush(200);
    // 驳回不可撤销：确认框说明后再执行
    expect(a.crud.execute).not.toHaveBeenCalled();
    expect(document.querySelector('[data-testid="op-confirm-body"]')!.textContent).toContain('此操作不能撤销');
    (document.querySelector('.ls-op-confirm-box .el-button--primary') as HTMLElement).click();
    await flush(300);
    expect(a.crud.execute).toHaveBeenCalledWith(
      C + '.reject',
      { targetId: '41', version: 1, input: { auditOpinion: '数量不符' } },
      expect.any(String)
    );
    expect((a.crud.query as any).mock.calls.length).toBe(queries);

    // 在新页签打开
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    q('detail-newtab')!.click();
    expect(open).toHaveBeenCalledWith(expect.stringContaining('/lsmom/record/' + encodeURIComponent(C) + '/41'), '_blank');
    open.mockRestore();

    // 拖动左边缘调宽，宽度记住
    const dragger = document.querySelector('[data-testid="ledger-detail"] .el-drawer__dragger, .el-drawer__dragger') as HTMLElement;
    expect(dragger).not.toBeNull();
    const box = dragger.getBoundingClientRect();
    const before = (document.querySelector('[data-testid="ledger-detail"]') as HTMLElement).getBoundingClientRect().width;
    dragger.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, clientX: box.x, clientY: box.y + 10 }));
    document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: box.x - 80, clientY: box.y + 10 }));
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: box.x - 80, clientY: box.y + 10 }));
    window.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, clientX: box.x - 80, clientY: box.y + 10 }));
    await flush();
    expect(Number(localStorage.getItem('ls:ledger-detail-width'))).toBeGreaterThanOrEqual(Math.round(before + 60));

    // 关闭抽屉后才刷新列表
    (document.querySelector('[data-testid="ledger-detail"] .el-drawer__close-btn') as HTMLElement).click();
    await flush(500);
    expect((a.crud.query as any).mock.calls.length).toBe(queries + 1);
  });
});
