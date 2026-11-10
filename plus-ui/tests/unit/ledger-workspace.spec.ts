/**
 * 14.3：通用台账工作区——默认表单录入并经 Operation 提交、旧单按冻结表单版本只读详情、导入预检有错误行时不能确认。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
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
    exportFile: vi.fn()
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
});
