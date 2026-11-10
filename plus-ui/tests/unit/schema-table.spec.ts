/**
 * 7.7：SchemaTable 组件（ListSchema 驱动的列、筛选、排序、行/批量动作绑定 Operation、导出）。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import 'vxe-table/lib/style.css';
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

async function flush(ms = 30) {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await new Promise((r) => setTimeout(r, ms));
}

afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
  document.querySelectorAll('.el-overlay, .el-message').forEach((e) => e.remove());
});

const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
// vxe 的固定列会在左右固定区各渲染一份，只取可见的那份
const headerCells = (el: HTMLElement) => [...el.querySelectorAll('.vxe-table--header .vxe-header--column:not(.fixed--hidden)')] as HTMLElement[];
const headerTexts = (el: HTMLElement) => headerCells(el).map((th) => th.textContent?.trim());
const headerCell = (el: HTMLElement, text: string) => headerCells(el).find((th) => th.textContent?.trim() === text)!;

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
    const headers = headerTexts(el);
    expect(headers).toEqual(expect.arrayContaining(['借用单号', '借用人', '用途', '主要工器具', '状态', '操作']));
    expect(headers).not.toContain('附件');
    expect(el.textContent).toContain('扭矩扳手');
    expect(el.textContent).toContain('检修');
    expect(api.query).toHaveBeenCalledTimes(1);

    // 字段筛选在「高级筛选」抽屉里
    q(el, 'adv-open')!.click();
    await flush(300);
    const doc = document.body;
    const target = q(doc, 'filter-loanNo')!;
    const input = (target.tagName === 'INPUT' ? target : target.querySelector('input')) as HTMLInputElement;
    input.value = 'L-2';
    input.dispatchEvent(new Event('input'));
    await flush();
    q(doc, 'search')!.click();
    await flush();
    const spec = (api.query as any).mock.calls.at(-1)[1];
    expect(spec.filters).toEqual([{ field: 'loanNo', op: 'like', value: 'L-2' }]);
    // 条件以可删除的标签显示
    expect(q(el, 'cond-loanNo')?.textContent).toContain('借用单号 包含 L-2');
    // 未开放查询的字段没有筛选控件
    expect(q(doc, 'filter-urgent')).toBeNull();
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
    // 没有勾选时不显示批量操作栏
    expect(q(el, 'batch-bar')).toBeNull();
    (el.querySelector('.vxe-header--column.col--checkbox:not(.fixed--hidden) .vxe-cell--checkbox') as HTMLElement).click();
    await flush();
    expect(q(el, 'batch-count')!.textContent).toContain('已选 2 条');
    expect(q(el, 'batch-acme.tool.LoanOrder.reject')).toBeNull();
    q(el, 'batch-acme.tool.LoanOrder.submit')!.click();
    await flush(80);
    // 第 2 条是 SUBMITTED：当前状态不允许，不发请求直接记为失败
    expect(api.execute).toHaveBeenCalledTimes(1);
    expect(events.at(-1)?.payload).toMatchObject({ ok: 1, failed: [{ id: 2 }] });
    expect(document.querySelector('[data-testid="batch-failed"]')!.textContent).toContain('不能提交');
  });

  it('排序与导出：可排序列触发带 sort 的查询，导出提交当前筛选条件', async () => {
    const api = fakeApi();
    const { el, events } = await mount(api);
    const sortable = headerCell(el, '借用单号');
    expect(sortable.classList.contains('is--sortable')).toBe(true);
    (sortable.querySelector('.vxe-cell') as HTMLElement).click();
    await flush();
    expect((api.query as any).mock.calls.at(-1)[1].sort).toEqual([{ field: 'loanNo', dir: 'asc' }]);
    expect(headerCell(el, '借用人').classList.contains('is--sortable')).toBe(false);

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

describe('SchemaTable 列设置与大数据量（modernize-ui-ux 5.1）', () => {
  async function mountWith(api: CrudApi, props: Record<string, unknown> = {}) {
    host = document.createElement('div');
    document.body.appendChild(host);
    const events: { name: string; payload: any }[] = [];
    app = createApp(SchemaTable, {
      concept: schema.concept,
      api,
      ...props,
      onColumnsChange: (p: unknown) => events.push({ name: 'columns-change', payload: p }),
      onColumnsDropped: (p: unknown) => events.push({ name: 'columns-dropped', payload: p })
    });
    app.use(ElementPlus).mount(host);
    await flush();
    return { el: host, events };
  }

  it('隐藏、固定、移动列并通知调用方；恢复默认；套用保存的列设置时忽略已弃用字段', async () => {
    const { el, events } = await mountWith(fakeApi());
    q(el, 'col-settings')!.click();
    await flush();
    (document.querySelector('[data-testid="col-visible-borrower"] input') as HTMLInputElement).click();
    await flush();
    expect(headerTexts(el)).not.toContain('借用人');
    expect(events.at(-1)?.payload.find((c: any) => c.code === 'borrower').visible).toBe(false);

    (document.querySelector('[data-testid="col-fix-purpose"]') as HTMLElement).click();
    await flush();
    expect(events.at(-1)?.payload.find((c: any) => c.code === 'purpose').fixed).toBe('left');
    expect(el.querySelector('.vxe-table--fixed-left-wrapper')?.textContent).toContain('用途');

    (document.querySelector('[data-testid="col-loanDate"] button[aria-label="上移借出日期"]') as HTMLElement).click();
    await flush();
    const order = events.at(-1)!.payload.map((c: any) => c.code);
    expect(order.indexOf('loanDate')).toBeLessThan(order.indexOf('borrower'));

    (document.querySelector('[data-testid="col-reset"]') as HTMLElement).click();
    await flush();
    expect(headerTexts(el)).toContain('借用人');
    app!.unmount();
    host!.remove();

    const saved = [
      { code: 'borrower', visible: true, width: 200 },
      { code: 'gone', visible: true },
      { code: 'loanNo', visible: false }
    ];
    const r = await mountWith(fakeApi(), { columns: saved });
    expect(r.events.find((e) => e.name === 'columns-dropped')?.payload).toEqual(['gone']);
    const texts = headerTexts(r.el);
    expect(texts).not.toContain('借用单号');
    expect(texts.indexOf('借用人')).toBeLessThan(texts.indexOf('借出日期'));
  });

  it('一万行：虚拟滚动只渲染可见行，滚动期间没有超过 50ms 的长任务', async () => {
    const many = Array.from({ length: 10000 }, (_, i) => ({
      id: i + 1,
      version: 0,
      status: i % 3 ? 'SUBMITTED' : 'DRAFT',
      loanNo: `L-${i + 1}`,
      borrower: `借用人${i % 97}`,
      purpose: i % 2 ? 'OTHER' : 'MAINTENANCE',
      toolRef: 7,
      toolRefRef: { id: 7, label: '扭矩扳手' }
    }));
    const api = fakeApi({ query: vi.fn(async () => ({ rows: many, total: many.length, page: 1, size: many.length })) as any });
    const { el } = await mountWith(api, { pageSize: 10000, maxHeight: 600 });
    await new Promise((r) => setTimeout(r, 300));
    const body = el.querySelector('.vxe-table--main-wrapper .vxe-table--body-wrapper') as HTMLElement;
    const scroller = (body.querySelector('.vxe-table--body-inner-wrapper') as HTMLElement) ?? body;
    expect(el.querySelectorAll('.vxe-table--main-wrapper .vxe-body--row').length).toBeLessThan(120);

    const longTasks: number[] = [];
    const obs = new PerformanceObserver((list) => list.getEntries().forEach((e) => longTasks.push(e.duration)));
    obs.observe({ type: 'longtask', buffered: false });
    for (let i = 1; i <= 80; i++) {
      scroller.scrollTop = i * 1200;
      scroller.dispatchEvent(new Event('scroll'));
      await new Promise((r) => requestAnimationFrame(() => r(null)));
    }
    await new Promise((r) => setTimeout(r, 200));
    obs.disconnect();
    expect(scroller.scrollTop).toBeGreaterThan(50000);
    expect(el.querySelector('.vxe-table--main-wrapper')!.textContent).toMatch(/L-\d{4}/);
    expect(Math.max(0, ...longTasks), `长任务：${longTasks.map((d) => d.toFixed(0)).join(', ')}`).toBeLessThan(50);
  });
});

describe('SchemaTable 关键字、快速筛选、条件标签与保存视图（modernize-ui-ux 5.2/5.3）', () => {
  const withStates = {
    ...schema,
    states: [
      { code: 'DRAFT', label: '草稿', initial: true, terminal: false },
      { code: 'SUBMITTED', label: '已提交', initial: false, terminal: false },
      { code: 'DONE', label: '已归还', initial: false, terminal: true },
      { code: 'CANCELLED', label: '已取消', initial: false, terminal: true }
    ]
  };
  function fakeViews(initial: any[] = [], defaultId: number | null = null) {
    const store = { views: [...initial], defaultId };
    let seq = 100;
    return {
      store,
      api: {
        list: vi.fn(async () => ({ views: store.views.map((v) => ({ ...v })), defaultId: store.defaultId })),
        create: vi.fn(async (b: any) => {
          const id = ++seq;
          store.views.push({ id, name: b.name, mine: true, sharedRole: b.sharedRole, definition: JSON.parse(JSON.stringify(b.definition)) });
          return { id };
        }),
        update: vi.fn(async (id: number, b: any) => Object.assign(store.views.find((v) => v.id === id)!, { name: b.name, definition: b.definition })),
        remove: vi.fn(async (id: number) => (store.views = store.views.filter((v) => v.id !== id))),
        setDefault: vi.fn(async (_c: string, id: number | null) => (store.defaultId = id)),
        roles: vi.fn(async () => []),
        context: vi.fn(async () => ({ orgId: 18, partyId: 4 }))
      } as any
    };
  }
  async function mountT(api: CrudApi, viewApi: any) {
    host = document.createElement('div');
    document.body.appendChild(host);
    app = createApp(SchemaTable, { concept: schema.concept, api, viewApi });
    app.use(ElementPlus).mount(host);
    await flush(60);
    return host;
  }
  const inputOf = (t: Element) => (t.tagName === 'INPUT' ? t : t.querySelector('input')) as HTMLInputElement;
  const lastSpec = (api: CrudApi) => (api.query as any).mock.calls.at(-1)[1];

  it('关键字跨字段搜索；"本组织""未完成"芯片按当前用户解析；标签可逐个删除', async () => {
    const api = fakeApi({ schema: vi.fn(async () => withStates) as any });
    const { api: va } = fakeViews();
    const el = await mountT(api, va);
    const kw = inputOf(q(el, 'keyword')!);
    kw.value = '扳手';
    kw.dispatchEvent(new Event('input'));
    kw.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter' }));
    await flush();
    expect(lastSpec(api).filters).toEqual([{ field: '_keyword', op: 'like', value: '扳手' }]);

    q(el, 'chip-myOrg')!.click();
    await flush();
    q(el, 'chip-open')!.click();
    await flush();
    expect(lastSpec(api).filters).toEqual([
      { field: 'orgId', op: 'eq', value: 18 },
      { field: 'status', op: 'notIn', value: ['DONE', 'CANCELLED'] },
      { field: '_keyword', op: 'like', value: '扳手' }
    ]);
    expect(q(el, 'chip-open')!.className).toContain('is-checked');
    const labels = [...el.querySelectorAll('[data-testid="conds"] .el-tag')].map((t) => t.textContent?.trim());
    expect(labels).toEqual(['本组织', '未完成', '关键字：扳手']);

    (q(el, 'cond-_keyword')!.querySelector('.el-tag__close') as HTMLElement).click();
    await flush();
    expect(lastSpec(api).filters.map((f: any) => f.field)).toEqual(['orgId', 'status']);
    q(el, 'chip-myOrg')!.click();
    await flush();
    expect(lastSpec(api).filters.map((f: any) => f.field)).toEqual(['status']);
    q(el, 'cond-clear')!.click();
    await flush();
    expect(lastSpec(api).filters).toEqual([]);
  });

  it('保存"本班组未完成"并设为默认：再次打开时自动应用（变量保存为 $context）；弃用字段忽略并提示', async () => {
    const api = fakeApi({ schema: vi.fn(async () => withStates) as any });
    const views = fakeViews();
    let el = await mountT(api, views.api);
    const kw = inputOf(q(el, 'keyword')!);
    kw.value = '扳手';
    kw.dispatchEvent(new Event('input'));
    await flush();
    q(el, 'chip-myOrg')!.click();
    await flush();
    q(el, 'chip-open')!.click();
    await flush();
    q(el, 'view-save')!.click();
    await flush(300);
    const name = inputOf(document.querySelector('[data-testid="view-name"]')!);
    name.value = '本班组未完成';
    name.dispatchEvent(new Event('input'));
    inputOf(document.querySelector('[data-testid="view-as-default"]')!).click();
    await flush();
    (document.querySelector('[data-testid="view-save-confirm"]') as HTMLElement).click();
    await flush(60);
    const saved = views.store.views[0];
    expect(saved.definition.filters).toEqual([
      { field: 'orgId', op: 'eq', value: '$context.orgId' },
      { field: 'status', op: 'notIn', value: ['DONE', 'CANCELLED'] }
    ]);
    // 关键字单独保存（服务端只接受台账字段作为筛选）
    expect(saved.definition.keyword).toBe('扳手');
    expect(views.store.defaultId).toBe(saved.id);
    app!.unmount();
    host!.remove();

    // 再次打开：第一次查询就带上默认视图的条件
    const api2 = fakeApi({ schema: vi.fn(async () => withStates) as any });
    el = await mountT(api2, views.api);
    expect(api2.query).toHaveBeenCalledTimes(1);
    expect(lastSpec(api2).filters).toEqual([
      { field: 'orgId', op: 'eq', value: 18 },
      { field: 'status', op: 'notIn', value: ['DONE', 'CANCELLED'] },
      { field: '_keyword', op: 'like', value: '扳手' }
    ]);
    expect(inputOf(q(el, 'keyword')!).value).toBe('扳手');
    const sel = q(el, 'view-select')!;
    expect((sel.closest('.el-select') ?? sel).textContent).toContain('本班组未完成');
    app!.unmount();
    host!.remove();

    // 视图里有已弃用的字段：忽略并提示，其余条件照常
    views.store.views[0].definition.filters.push({ field: 'oldField', op: 'eq', value: 'x' });
    views.store.views[0].definition.sort = [{ field: 'oldSort', dir: 'asc' }];
    const api3 = fakeApi({ schema: vi.fn(async () => withStates) as any });
    await mountT(api3, views.api);
    expect(lastSpec(api3).filters.map((f: any) => f.field)).toEqual(['orgId', 'status', '_keyword']);
    expect([...document.querySelectorAll('.el-message')].map((m) => m.textContent).join('|')).toContain('oldField、oldSort 已停用，已忽略');
  });
});

describe('SchemaTable 行内编辑草稿与行内动作（modernize-ui-ux 5.5）', () => {
  const W = 'acme.work.Job';
  const wschema = {
    concept: W,
    displayName: '工作',
    version: 1,
    fields: [
      { code: 'title', label: '标题', type: 'STRING', required: true, queryable: true, sortable: true, writable: true },
      { code: 'qty', label: '数量', type: 'INTEGER', required: false, queryable: false, sortable: false, writable: true },
      { code: 'locked', label: '编号', type: 'STRING', required: false, queryable: false, sortable: false, writable: false }
    ],
    children: [],
    states: [
      { code: 'DRAFT', label: '草稿', initial: true, terminal: false },
      { code: 'ASSIGNED', label: '已分派', initial: false, terminal: false },
      { code: 'DONE', label: '已完成', initial: false, terminal: true }
    ],
    operations: [
      { operation: W + '.submit', name: '提交', kind: 'TRANSITION', risk: 'C1', permitted: true, from: ['DRAFT'] },
      { operation: W + '.assign', name: '分派', kind: 'TRANSITION', risk: 'C1', permitted: true, from: ['DRAFT', 'ASSIGNED'] },
      { operation: W + '.cancel', name: '取消', kind: 'TRANSITION', risk: 'C1', permitted: true, from: ['DRAFT', 'ASSIGNED'] },
      {
        operation: W + '.note',
        name: '加备注',
        kind: 'TRANSITION',
        risk: 'C1',
        permitted: true,
        from: ['DRAFT'],
        inputSchema: { type: 'object', required: ['note'], properties: { note: { type: 'string' } } }
      },
      { operation: W + '.reopen', name: '重开', kind: 'TRANSITION', risk: 'C1', permitted: false, from: ['DONE'] }
    ],
    permissions: { list: true, query: true, add: true, edit: true, remove: false, export: false, import: false }
  };
  const wrows = () => [
    { id: 1, version: 2, status: 'DRAFT', title: '换泵', qty: 3, locked: 'J-1' },
    { id: 2, version: 5, status: 'DONE', title: '巡检', qty: 1, locked: 'J-2' }
  ];
  async function mountW(api: CrudApi) {
    host = document.createElement('div');
    document.body.appendChild(host);
    const events: { name: string; payload: any }[] = [];
    app = createApp(SchemaTable, {
      concept: W,
      api,
      onRowAction: (p: unknown) => events.push({ name: 'row-action', payload: p }),
      onEdit: (p: unknown) => events.push({ name: 'edit', payload: p })
    });
    app.use(ElementPlus).mount(host);
    await flush(80);
    return { el: host, events };
  }
  const dbl = async (el: Element) => {
    el.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    await flush(60);
  };
  const editorInput = (code: string, id: number) => {
    const t = document.querySelector(`[data-testid="cell-editor-${code}-${id}"]`)!;
    return (t.tagName === 'INPUT' ? t : t.querySelector('input')) as HTMLInputElement;
  };

  it('已完成的工作不显示"分派""提交"；草稿行前 2 个动作直接显示，其余在"更多"；需要输入的动作交给详情办理', async () => {
    const api = fakeApi({
      schema: vi.fn(async () => wschema) as any,
      query: vi.fn(async () => ({ rows: wrows(), total: 2, page: 1, size: 20 })) as any
    });
    const { el, events } = await mountW(api);
    // 已完成：没有任何动作按钮（不置灰）
    expect(q(el, `op-${W}.assign-2`)).toBeNull();
    expect(q(el, `op-${W}.submit-2`)).toBeNull();
    expect(q(el, 'row-more-2')).toBeNull();
    expect(q(el, `op-${W}.reopen-2`)).toBeNull();
    // 草稿：编辑、提交直接显示，分派/取消/加备注进"更多"
    expect(q(el, 'row-edit-1')).not.toBeNull();
    expect(q(el, `op-${W}.submit-1`)).not.toBeNull();
    expect(q(el, `op-${W}.assign-1`)).toBeNull();
    q(el, 'row-more-1')!.click();
    await flush(250);
    const items = [...document.querySelectorAll('.el-dropdown-menu__item')].map((i) => i.textContent?.trim());
    expect(items).toEqual(['分派', '取消', '加备注']);
    (document.querySelector(`[data-testid="op-${W}.note-1"]`) as HTMLElement).click();
    await flush();
    expect(api.execute).not.toHaveBeenCalled();
    expect(events.find((e) => e.name === 'row-action')?.payload).toMatchObject({ operation: W + '.note', row: { id: 1 } });
  });

  it('双击草稿单元格行内编辑，Enter 保存并显示已保存；非草稿、不可写字段不能编辑；服务端拒绝时保留原值并标出原因', async () => {
    const update = vi.fn(async (_c: string, _id: number, version: number) => ({ id: 1, version: version + 1 }));
    const api = fakeApi({
      schema: vi.fn(async () => wschema) as any,
      query: vi.fn(async () => ({ rows: wrows(), total: 2, page: 1, size: 20 })) as any,
      update: update as any
    });
    const { el } = await mountW(api);
    // 非草稿行、不可写字段没有编辑入口
    expect(q(el, 'cell-qty-2')).toBeNull();
    expect(q(el, 'cell-locked-1')).toBeNull();
    const cell2 = [...el.querySelectorAll('.vxe-table--main-wrapper .vxe-body--row')][1].querySelector('[colid] .vxe-cell, td .vxe-cell')!;
    await dbl(cell2);
    expect(document.querySelector('[data-testid^="cell-editor-"]')).toBeNull();

    await dbl(q(el, 'cell-qty-1')!);
    const input = editorInput('qty', 1);
    expect(document.activeElement).toBe(input);
    input.value = '5';
    input.dispatchEvent(new Event('input'));
    await flush();
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await flush(60);
    expect(update).toHaveBeenCalledWith(W, 1, 2, { qty: 5 }, expect.any(String));
    expect(q(el, 'cell-state-qty-1')!.textContent).toContain('已保存');
    expect(q(el, 'cell-qty-1')!.textContent).toBe('5');

    // 失焦也保存；带上新版本号
    await dbl(q(el, 'cell-title-1')!);
    const t = editorInput('title', 1);
    t.value = '换泵（二号）';
    t.dispatchEvent(new Event('input'));
    t.dispatchEvent(new Event('blur'));
    await flush(60);
    expect(update).toHaveBeenLastCalledWith(W, 1, 3, { title: '换泵（二号）' }, expect.any(String));

    // Esc 取消，不保存
    await dbl(q(el, 'cell-title-1')!);
    const t2 = editorInput('title', 1);
    t2.value = '不要了';
    t2.dispatchEvent(new Event('input'));
    t2.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await flush(60);
    expect(update).toHaveBeenCalledTimes(2);
    expect(q(el, 'cell-title-1')!.textContent).toBe('换泵（二号）');

    // 服务端拒绝
    update.mockRejectedValueOnce(Object.assign(new Error('输入校验失败'), { details: { fields: { qty: '数量不能超过 3' } } }) as never);
    await dbl(q(el, 'cell-qty-1')!);
    const n = editorInput('qty', 1);
    n.value = '9';
    n.dispatchEvent(new Event('input'));
    n.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await flush(60);
    expect(q(el, 'cell-qty-1')!.textContent).toBe('5');
    const mark = q(el, 'cell-state-qty-1')!;
    expect(mark.textContent).toContain('未保存');
    expect(mark.getAttribute('title')).toBe('数量不能超过 3');
  });
});

describe('SchemaTable 批量操作栏（modernize-ui-ux 5.6）', () => {
  const B = 'acme.work.Job';
  const bschema = {
    concept: B,
    displayName: '工作',
    version: 1,
    fields: [{ code: 'title', label: '标题', type: 'STRING', required: true, queryable: true, sortable: true, writable: true }],
    children: [],
    states: [
      { code: 'READY', label: '待分派', initial: true, terminal: false },
      { code: 'ASSIGNED', label: '已分派', initial: false, terminal: false }
    ],
    operations: [{ operation: B + '.assign', name: '分派', kind: 'TRANSITION', risk: 'C1', permitted: true, from: ['READY'] }],
    permissions: { list: true, query: true, add: false, edit: false, remove: false, export: true, import: false }
  };
  const all = Array.from({ length: 45 }, (_, i) => ({ id: i + 1, version: 1, status: 'READY', title: `工作${i + 1}` }));
  function api(failIds: Set<number>) {
    const store = new Map(all.map((r) => [r.id, { ...r }]));
    return fakeApi({
      schema: vi.fn(async () => bschema) as any,
      query: vi.fn(async (_c: string, sp: any) => {
        const list = [...store.values()];
        const from = (sp.page - 1) * sp.size;
        return { rows: list.slice(from, from + sp.size).map((r) => ({ ...r })), total: list.length, page: sp.page, size: sp.size };
      }) as any,
      get: vi.fn(async (_c: string, id: number) => ({ ...store.get(Number(id))! })) as any,
      execute: vi.fn(async (_op: string, call: any) => {
        const id = Number(call.targetId);
        if (failIds.has(id)) throw new Error(`工作${id}的负责人资质已过期`);
        const r = store.get(id)!;
        Object.assign(r, { status: 'ASSIGNED', version: r.version + 1 });
        return { targetId: call.targetId, version: r.version, status: 'ASSIGNED', warnings: [] };
      }) as any
    });
  }
  async function mountB(a: CrudApi) {
    host = document.createElement('div');
    document.body.appendChild(host);
    app = createApp(SchemaTable, { concept: B, api: a, pageSize: 20 });
    app.use(ElementPlus).mount(host);
    await flush(80);
    return host;
  }
  const checkAll = (el: HTMLElement) =>
    (el.querySelector('.vxe-header--column.col--checkbox:not(.fixed--hidden) .vxe-cell--checkbox') as HTMLElement).click();

  it('20 条批量分派中 2 条失败：显示逐条结果，重试只重试失败项', async () => {
    const fails = new Set([4, 9]);
    const a = api(fails);
    const el = await mountB(a);
    checkAll(el);
    await flush();
    expect(q(el, 'batch-count')!.textContent).toContain('已选 20 条');
    q(el, 'batch-' + B + '.assign')!.click();
    await flush(150);
    expect(a.execute).toHaveBeenCalledTimes(20);
    const summary = document.querySelector('[data-testid="batch-summary"]')!.textContent!;
    expect(summary).toContain('共 20 条：成功 18 条，失败 2 条');
    const failedText = document.querySelector('[data-testid="batch-failed"]')!.textContent!;
    expect(failedText).toContain('工作4的负责人资质已过期');
    expect(failedText).toContain('工作9');
    // 改派后重试：只发失败的 2 条，带最新版本
    fails.clear();
    (a.execute as any).mockClear();
    (document.querySelector('[data-testid="batch-retry"]') as HTMLElement).click();
    await flush(150);
    expect((a.execute as any).mock.calls.map((c: any) => c[1].targetId).sort()).toEqual(['4', '9']);
    expect(document.querySelector('[data-testid="batch-summary"]')!.textContent).toContain('成功 20 条，失败 0 条');
    expect(document.querySelector('[data-testid="batch-retry"]')).toBeNull();
  });

  it('跨页全选需要二次确认，确认后对全部筛选结果执行；导出所选走异步导出', async () => {
    const a = api(new Set());
    const el = await mountB(a);
    checkAll(el);
    await flush();
    q(el, 'batch-select-all')!.click();
    await flush(200);
    expect(document.querySelector('.el-message-box')!.textContent).toContain('全部 45 条筛选结果');
    (document.querySelector('.el-message-box .el-button--primary') as HTMLElement).click();
    await flush(200);
    expect(q(el, 'batch-count')!.textContent).toContain('已选 45 条（全部筛选结果）');

    // 导出全部筛选结果：确认后提交导出任务（不带编号条件）
    q(el, 'batch-export')!.click();
    await flush(200);
    expect(document.querySelector('.el-message-box')!.textContent).toContain('导出全部 45 条');
    (document.querySelector('.el-message-box .el-button--primary') as HTMLElement).click();
    await flush(200);
    expect((a.exportJob as any).mock.calls.at(-1)[1].filters).toEqual([]);

    q(el, 'batch-' + B + '.assign')!.click();
    await flush(200);
    expect(document.querySelector('.el-message-box')!.textContent).toContain('将对 45 条记录执行「分派」');
    (document.querySelector('.el-message-box .el-button--primary') as HTMLElement).click();
    await flush(300);
    expect(a.execute).toHaveBeenCalledTimes(45);
    expect(document.querySelector('[data-testid="batch-summary"]')!.textContent).toContain('成功 45 条');
  });

  it('只勾选本页部分记录时导出所选按编号导出', async () => {
    const a = api(new Set());
    const el = await mountB(a);
    (el.querySelectorAll('.vxe-body--column.col--checkbox:not(.fixed--hidden) .vxe-cell--checkbox')[1] as HTMLElement).click();
    await flush();
    expect(q(el, 'batch-select-all')).toBeNull();
    q(el, 'batch-export')!.click();
    await flush(100);
    expect((a.exportJob as any).mock.calls.at(-1)[1].filters).toEqual([{ field: 'id', op: 'in', value: [2] }]);
  });
});

describe('SchemaTable "有 N 条更新"（modernize-ui-ux 5.7）', () => {
  it('他人新增后提示条数，不自动刷新、勾选保持；点刷新后列表更新且勾选仍在', async () => {
    let list = [
      { id: 2, version: 1, status: 'DRAFT', loanNo: 'L-2', updatedAt: '2026-11-10 08:00:00' },
      { id: 1, version: 1, status: 'DRAFT', loanNo: 'L-1', updatedAt: '2026-11-10 07:00:00' }
    ];
    const aggregate = vi.fn(async (_c: string, sp: any) => {
      const since = sp.filters.find((f: any) => f.field === 'updatedAt' && f.op === 'gt')?.value;
      if (since) return [{ n: list.filter((r) => r.updatedAt.replace(' ', 'T') > since).length }];
      const u = list
        .map((r) => r.updatedAt)
        .sort()
        .at(-1);
      return [{ u, c: null }];
    });
    const query = vi.fn(async () => ({ rows: list.map((r) => ({ ...r })), total: list.length, page: 1, size: 20 }));
    const api = fakeApi({ query: query as any, aggregate: aggregate as any });
    host = document.createElement('div');
    document.body.appendChild(host);
    app = createApp(SchemaTable, { concept: schema.concept, api, pollInterval: 150 });
    app.use(ElementPlus).mount(host);
    await flush(100);
    const el = host;
    // 基准：当前最新的修改时间；同一秒的已计入基数
    expect(aggregate.mock.calls[0][1].aggregates).toEqual([
      { fn: 'max', field: 'updatedAt', alias: 'u' },
      { fn: 'max', field: 'createdAt', alias: 'c' }
    ]);
    await flush(250);
    expect(q(el, 'updates-banner')).toBeNull();

    // 勾选第 1 行
    (el.querySelectorAll('.vxe-body--column.col--checkbox:not(.fixed--hidden) .vxe-cell--checkbox')[1] as HTMLElement).click();
    await flush();
    const queries = query.mock.calls.length;
    // 他人新增一条
    list = [{ id: 3, version: 0, status: 'DRAFT', loanNo: 'L-3', updatedAt: '2026-11-10 09:00:00' }, ...list];
    await flush(350);
    expect(q(el, 'updates-banner')!.textContent).toContain('有 1 条更新');
    expect(query.mock.calls.length).toBe(queries); // 不自动刷新
    expect(el.querySelectorAll('.vxe-table--main-wrapper .vxe-body--row').length).toBe(2);
    expect(q(el, 'batch-count')!.textContent).toContain('已选 1 条');

    q(el, 'updates-refresh')!.click();
    await flush(200);
    expect(el.querySelectorAll('.vxe-table--main-wrapper .vxe-body--row').length).toBe(3);
    expect(q(el, 'updates-banner')).toBeNull();
    // 勾选保持（仍是 L-1）
    expect(q(el, 'batch-count')!.textContent).toContain('已选 1 条');
    const checked = el.querySelectorAll('.vxe-table--main-wrapper .vxe-body--row.row--checked');
    expect(checked.length).toBe(1);
    expect(checked[0].textContent).toContain('L-1');
  });

  it('pollInterval 为 0 时不检查', async () => {
    const aggregate = vi.fn(async () => [{ n: 0 }]);
    const api = fakeApi({ aggregate: aggregate as any });
    host = document.createElement('div');
    document.body.appendChild(host);
    app = createApp(SchemaTable, { concept: schema.concept, api, pollInterval: 0 });
    app.use(ElementPlus).mount(host);
    await flush(300);
    expect(aggregate).not.toHaveBeenCalled();
  });
});
