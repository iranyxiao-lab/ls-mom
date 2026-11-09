/**
 * 7.7：SchemaTable 组件（ListSchema 驱动的列、筛选、排序、行/批量动作绑定 Operation、导出）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import SchemaTable from '@/components/SchemaTable/index.vue';
import { buildQuerySpec, cellText, columnsOf } from '@/components/SchemaTable/querySpec';
import type { CrudApi } from '@/api/lsmom/crud';
import type { ListSchema } from '@/api/lsmom/types';

const schema: ListSchema = {
  concept: 'acme.tool.LoanOrder',
  displayName: '工器具借还单',
  version: 1,
  fields: [
    { code: 'loanNo', label: '借用单号', type: 'STRING', required: true, queryable: true, sortable: true, writable: true },
    { code: 'borrower', label: '借用人', type: 'STRING', required: true, queryable: true, sortable: false, writable: true },
    { code: 'loanDate', label: '借出日期', type: 'DATE', required: true, queryable: true, sortable: true, writable: true },
    {
      code: 'purpose',
      label: '用途',
      type: 'ENUM',
      required: false,
      queryable: true,
      sortable: false,
      writable: true,
      options: ['MAINTENANCE', 'OTHER'],
      optionLabels: { MAINTENANCE: '检修', OTHER: '其他' }
    },
    { code: 'urgent', label: '是否加急', type: 'STRING', required: false, queryable: false, sortable: false, writable: true, dictType: 'sys_yes_no' },
    {
      code: 'toolRef',
      label: '主要工器具',
      type: 'REF',
      required: false,
      queryable: false,
      sortable: false,
      writable: true,
      refConcept: 'acme.tool.Tool'
    },
    { code: 'attachments', label: '附件', type: 'FILE', required: false, queryable: false, sortable: false, writable: true }
  ],
  children: [],
  operations: [
    {
      operation: 'acme.tool.LoanOrder.submit',
      name: '提交',
      kind: 'TRANSITION',
      risk: 'C1',
      permitted: true,
      inputSchema: { type: 'object' },
      from: ['DRAFT']
    },
    {
      operation: 'acme.tool.LoanOrder.reject',
      name: '退回',
      kind: 'TRANSITION',
      risk: 'C1',
      permitted: true,
      inputSchema: { type: 'object', required: ['reason'] },
      from: ['SUBMITTED']
    },
    { operation: 'acme.tool.LoanOrder.approve', name: '审核', kind: 'TRANSITION', risk: 'C1', permitted: false, from: ['SUBMITTED'] }
  ],
  permissions: { list: true, query: true, add: true, edit: true, remove: true, export: true, import: false }
};

const rows = [
  {
    id: 1,
    version: 0,
    status: 'DRAFT',
    loanNo: 'L-1',
    borrower: '张三',
    purpose: 'MAINTENANCE',
    urgent: 'Y',
    urgentLabel: '是',
    toolRef: 7,
    toolRefRef: { id: 7, label: '扭矩扳手' }
  },
  { id: 2, version: 3, status: 'SUBMITTED', loanNo: 'L-2', borrower: '李四', purpose: 'OTHER', toolRef: 8, toolRefRef: { id: 8, restricted: true } }
];

function fakeApi(over: Partial<CrudApi> = {}) {
  return {
    schema: vi.fn(async () => schema),
    query: vi.fn(async () => ({ rows, total: 2, page: 1, size: 20 })),
    execute: vi.fn(async () => ({ targetId: '1', version: 1, status: 'SUBMITTED', warnings: [] })),
    exportJob: vi.fn(async () => ({ id: 9, concept: schema.concept, status: 'PENDING' })),
    ...over
  } as unknown as CrudApi;
}

let app: App | null = null;
let host: HTMLElement | null = null;

async function mount(api: CrudApi) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const events: { name: string; payload: unknown }[] = [];
  app = createApp(SchemaTable, {
    concept: schema.concept,
    api,
    onActionDone: (p: unknown) => events.push({ name: 'action-done', payload: p }),
    onCreate: () => events.push({ name: 'create', payload: null })
  });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return { el: host, events };
}

async function flush() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await new Promise((r) => setTimeout(r, 30));
}

afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
});

const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

describe('SchemaTable 辅助函数', () => {
  it('只为白名单字段生成筛选条件，空值忽略，排序只接受可排序字段', () => {
    const s = buildQuerySpec(
      schema,
      { loanNo: 'L-', borrower: '', purpose: 'MAINTENANCE', loanDate: ['2026-11-01', '2026-11-30'], urgent: 'Y' },
      null,
      2,
      50
    );
    expect(s.filters).toEqual([
      { field: 'loanNo', op: 'like', value: 'L-' },
      { field: 'loanDate', op: 'between', value: ['2026-11-01', '2026-11-30'] },
      { field: 'purpose', op: 'eq', value: 'MAINTENANCE' }
    ]);
    expect(s.page).toBe(2);
    expect(s.expand).toEqual(['toolRef']);
    expect(buildQuerySpec(schema, {}, { field: 'borrower', dir: 'asc' }, 1, 20).sort).toEqual([]);
    expect(buildQuerySpec(schema, {}, { field: 'loanNo', dir: 'desc' }, 1, 20).sort).toEqual([{ field: 'loanNo', dir: 'desc' }]);
  });

  it('列来自 LIST 视图或字段，单元格显示字典、枚举、引用标签', () => {
    expect(columnsOf(schema).map((c) => c.code)).not.toContain('attachments');
    expect(columnsOf({ ...schema, view: JSON.stringify({ columns: ['borrower', 'loanNo', 'nope'] }) }).map((c) => c.code)).toEqual([
      'borrower',
      'loanNo'
    ]);
    const f = (code: string) => schema.fields.find((x) => x.code === code)!;
    expect(cellText(f('urgent'), rows[0])).toBe('是');
    expect(cellText(f('purpose'), rows[1])).toBe('其他');
    expect(cellText(f('toolRef'), rows[0])).toBe('扭矩扳手');
    expect(cellText(f('toolRef'), rows[1])).toBe('（无权查看）');
  });
});

describe('SchemaTable 组件', () => {
  it('按 ListSchema 渲染列与数据，筛选生成 QuerySpec', async () => {
    const api = fakeApi();
    const { el } = await mount(api);
    const headers = [...el.querySelectorAll('.el-table__header th')].map((th) => th.textContent?.trim());
    expect(headers).toEqual(expect.arrayContaining(['借用单号', '借用人', '用途', '主要工器具', '状态', '操作']));
    expect(headers).not.toContain('附件');
    expect(el.textContent).toContain('扭矩扳手');
    expect(el.textContent).toContain('检修');
    expect(api.query).toHaveBeenCalledTimes(1);

    const target = q(el, 'filter-loanNo')!;
    const input = (target.tagName === 'INPUT' ? target : target.querySelector('input')) as HTMLInputElement;
    input.value = 'L-2';
    input.dispatchEvent(new Event('input'));
    await flush();
    q(el, 'search')!.click();
    await flush();
    const spec = (api.query as any).mock.calls.at(-1)[1];
    expect(spec.filters).toEqual([{ field: 'loanNo', op: 'like', value: 'L-2' }]);
    // 未开放查询的字段没有筛选控件
    expect(q(el, 'filter-urgent')).toBeNull();
  });

  it('行动作只显示有权限且状态允许的 Operation，并带版本号调用网关', async () => {
    const api = fakeApi();
    const { el, events } = await mount(api);
    expect(q(el, 'op-acme.tool.LoanOrder.submit-1')).not.toBeNull();
    expect(q(el, 'op-acme.tool.LoanOrder.submit-2')).toBeNull(); // SUBMITTED 不能再提交
    expect(q(el, 'op-acme.tool.LoanOrder.approve-2')).toBeNull(); // 无权限
    q(el, 'op-acme.tool.LoanOrder.submit-1')!.click();
    await flush();
    expect(api.execute).toHaveBeenCalledWith('acme.tool.LoanOrder.submit', { targetId: '1', version: 0, input: {} });
    expect(events.find((e) => e.name === 'action-done')?.payload).toMatchObject({ ok: 1 });
    expect(api.query).toHaveBeenCalledTimes(2);
  });

  it('批量动作逐条执行并汇总失败；需要输入的动作不提供批量', async () => {
    const api = fakeApi({
      execute: vi.fn(async (_op: string, call: any) => {
        if (call.targetId === '2') throw new Error('当前状态不能执行');
        return { targetId: call.targetId, version: 1, status: 'SUBMITTED', warnings: [] };
      }) as any
    });
    const { el, events } = await mount(api);
    expect(q(el, 'batch-acme.tool.LoanOrder.reject')).toBeNull();
    const batch = q(el, 'batch-acme.tool.LoanOrder.submit') as HTMLButtonElement;
    expect(batch.disabled).toBe(true);
    (el.querySelector('.el-table__header .el-checkbox') as HTMLElement).click();
    await flush();
    expect(batch.disabled).toBe(false);
    batch.click();
    await flush();
    expect(api.execute).toHaveBeenCalledTimes(2);
    expect(events.at(-1)?.payload).toMatchObject({ ok: 1, failed: [{ id: 2, message: '当前状态不能执行' }] });
  });

  it('排序与导出：可排序列触发带 sort 的查询，导出提交当前筛选条件', async () => {
    const api = fakeApi();
    const { el, events } = await mount(api);
    const sortable = [...el.querySelectorAll('.el-table__header th')].find((th) => th.textContent?.trim() === '借用单号') as HTMLElement;
    expect(sortable.classList.contains('is-sortable')).toBe(true);
    (sortable.querySelector('.cell') as HTMLElement).click();
    await flush();
    expect((api.query as any).mock.calls.at(-1)[1].sort).toEqual([{ field: 'loanNo', dir: 'asc' }]);
    const borrowerTh = [...el.querySelectorAll('.el-table__header th')].find((th) => th.textContent?.trim() === '借用人') as HTMLElement;
    expect(borrowerTh.classList.contains('is-sortable')).toBe(false);

    q(el, 'export')!.click();
    await flush();
    expect(api.exportJob).toHaveBeenCalledWith(schema.concept, expect.objectContaining({ sort: [{ field: 'loanNo', dir: 'asc' }], page: 1 }));
    q(el, 'create')!.click();
    expect(events.some((e) => e.name === 'create')).toBe(true);
  });

  it('没有导出与新增权限时不显示按钮', async () => {
    const api = fakeApi({ schema: vi.fn(async () => ({ ...schema, permissions: { ...schema.permissions, export: false, add: false } })) as any });
    const { el } = await mount(api);
    expect(q(el, 'export')).toBeNull();
    expect(q(el, 'create')).toBeNull();
  });
});
