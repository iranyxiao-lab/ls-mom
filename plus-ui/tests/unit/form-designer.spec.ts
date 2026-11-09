/**
 * 8.6：设计器工作区（真实 form-create 设计器）——扩展面板、Schema 区手工编辑受校验、差异区、预览、权限模拟、≥50 步撤销重做。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import Workspace from '@/views/lsmom/form-designer/Workspace.vue';
import { installDesigner } from '@/views/lsmom/form-designer/install';
import type { FormSchema } from '@/types/lsView';
import { normalizeForm } from '@/utils/lsform/fcAdapter';

const schema: FormSchema = {
  viewType: 'FORM',
  formCode: 'acme.tool.loanForm',
  version: 2,
  conceptCode: 'acme.tool.LoanOrder',
  title: '工器具借还单',
  layout: [
    { type: 'field', key: 'loanNo' },
    { type: 'field', key: 'borrower' },
    { type: 'field', key: 'auditNote' },
    { type: 'subTable', key: 'loanLine' }
  ],
  fields: [
    { key: 'loanNo', label: '借用单号', widget: 'input', required: true },
    { key: 'borrower', label: '借用人', widget: 'input' },
    { key: 'auditNote', label: '审核意见', widget: 'textarea' }
  ],
  subTables: [{ key: 'loanLine', label: '借出明细', conceptCode: 'acme.tool.LoanLine', fields: [{ key: 'tool', label: '工器具', widget: 'input' }] }],
  actions: [{ key: 'submit', label: '提交', operation: 'acme.tool.LoanOrder.submit' }],
  nodePolicies: { deptAudit: { readonly: ['*'], editable: ['auditNote'] } }
};

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
});

async function flush(ms = 60) {
  for (let i = 0; i < 6; i++) await nextTick();
  await new Promise((r) => setTimeout(r, ms));
}

const q = (id: string) => host!.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
/** el-input 的 data-testid 落在 input/textarea 本身 */
const box = (id: string) => {
  const e = q(id)!;
  return (e.tagName === 'INPUT' || e.tagName === 'TEXTAREA' ? e : e.querySelector('input,textarea')) as HTMLInputElement;
};

async function mount(api: any, extra: Record<string, any> = {}) {
  host = document.createElement('div');
  host.style.width = '1500px';
  document.body.appendChild(host);
  const ws = ref<any>();
  app = createApp({
    setup: () => () =>
      h(Workspace, {
        ref: ws,
        schema,
        published: { ...schema, version: 1, fields: schema.fields.slice(0, 2), layout: schema.layout.filter((n) => n.key !== 'auditNote') },
        conceptFields: [
          { code: 'loanNo', label: '借用单号', type: 'STRING' },
          { code: 'borrower', label: '借用人', type: 'STRING', writePermission: 'acme:loan:edit' },
          { code: 'auditNote', label: '审核意见', type: 'TEXT' }
        ],
        operations: [{ operation: 'acme.tool.LoanOrder.submit', name: '提交' }],
        api,
        rendererApi: { refSearch: vi.fn(async () => []), upload: vi.fn(), dict: vi.fn(async () => []) },
        height: '700px',
        pollMs: 0,
        ...extra
      })
  });
  app.use(ElementPlus);
  installDesigner(app);
  app.mount(host);
  await flush(250);
  return ws;
}

function clickTab(label: string) {
  const t = [...host!.querySelectorAll('.el-tabs__item')].find((e) => e.textContent?.trim() === label) as HTMLElement;
  t.click();
}

describe('设计器工作区', () => {
  it('载入后与原 Schema 等价，扩展面板修改 LS 属性进入 Schema', async () => {
    const ws = await mount({ saveDraft: vi.fn(), check: vi.fn(), publish: vi.fn() });
    ws.value.snap();
    await flush();
    expect(normalizeForm(ws.value.current)).toEqual(normalizeForm(schema));
    ws.value.designer.triggerActive('loanNo');
    await flush();
    expect(q('field-panel')).not.toBeNull();
    const pattern = box('ext-pattern');
    pattern.value = '^L-[0-9]+$';
    pattern.dispatchEvent(new Event('input', { bubbles: true }));
    pattern.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    expect(ws.value.current.fields[0].pattern).toBe('^L-[0-9]+$');
  });

  it('支持至少 50 步撤销与重做', async () => {
    const ws = await mount({ saveDraft: vi.fn(), check: vi.fn(), publish: vi.fn() });
    for (let i = 1; i <= 60; i++) {
      ws.value.load({ ...schema, title: '标题 ' + i });
      await flush(0);
      ws.value.snap();
      await flush(0);
    }
    await flush();
    expect(ws.value.current.title).toBe('标题 60');
    for (let i = 0; i < 55; i++) {
      ws.value.undo();
      await flush(0);
    }
    await flush();
    expect(ws.value.current.title).toBe('标题 5');
    for (let i = 0; i < 10; i++) {
      ws.value.redo();
      await flush(0);
    }
    await flush();
    expect(ws.value.current.title).toBe('标题 15');
    expect(ws.value.history.size).toBeGreaterThanOrEqual(61);
  });

  it('撤销删除：字段及其属性恢复', async () => {
    const ws = await mount({ saveDraft: vi.fn(), check: vi.fn(), publish: vi.fn() });
    ws.value.snap();
    await flush();
    ws.value.load({ ...schema, fields: schema.fields.filter((f) => f.key !== 'loanNo'), layout: schema.layout.filter((n) => n.key !== 'loanNo') });
    await flush();
    ws.value.snap();
    await flush();
    expect(ws.value.current.fields.map((f: any) => f.key)).not.toContain('loanNo');
    ws.value.undo();
    await flush();
    expect(ws.value.current.fields[0]).toMatchObject({ key: 'loanNo', label: '借用单号', required: true });
  });

  it('Schema 区手工加入未知控件后发布失败并定位到字段；差异区显示新增字段', async () => {
    const publish = vi.fn(async () => {
      throw {
        response: {
          data: {
            code: 422,
            data: { errorCode: 'VIEW_INVALID', issues: [{ path: '/fields/1/widget', code: 'SCHEMA_enum', message: '不在允许的控件中' }] }
          }
        }
      };
    });
    const saveDraft = vi.fn(async () => ({
      type: 'FORM',
      issues: [{ path: '/fields/1/widget', code: 'SCHEMA_enum', message: '不在允许的控件中' }],
      fieldRefs: []
    }));
    const ws = await mount({ saveDraft, check: vi.fn(), publish });
    clickTab('Schema');
    await flush();
    expect(q('schema-diff')!.textContent).toContain('+ ');
    expect(q('schema-diff')!.textContent).toContain('auditNote');
    const ta = box('schema-text');
    const s = JSON.parse(ta.value);
    s.fields[1].widget = 'fancyPicker';
    ta.value = JSON.stringify(s);
    ta.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    q('apply-schema')!.click();
    await flush();
    expect(ws.value.current.fields[1].widget).toBe('fancyPicker');
    q('publish')!.click();
    await flush(100);
    expect(saveDraft).toHaveBeenCalledWith(
      expect.objectContaining({ fields: expect.arrayContaining([expect.objectContaining({ widget: 'fancyPicker' })]) })
    );
    expect(q('issues')!.textContent).toContain('/fields/1/widget');
    q('issue-0')!.click();
    await flush();
    expect(q('locate-hint')!.textContent).toContain('borrower');
  });

  it('预览使用运行时渲染器；权限模拟按节点与角色计算可编辑字段', async () => {
    await mount({ saveDraft: vi.fn(), check: vi.fn(), publish: vi.fn() });
    clickTab('预览');
    await flush();
    expect(q('preview')!.querySelector('[data-testid="schema-form"]')).not.toBeNull();
    clickTab('权限模拟');
    await flush();
    const sim = q('simulate')!;
    expect(sim.querySelector('[data-testid="input-borrower"]')).toBeNull(); // 缺写权限 → 只读
    expect(sim.querySelector('[data-testid="input-loanNo"]')).not.toBeNull();
  });

  it('子表列只能绑定所属子 Concept 的字段；选中子表可绑定子 Concept', async () => {
    const ws = await mount(
      { saveDraft: vi.fn(), check: vi.fn(), publish: vi.fn() },
      {
        conceptFields: [
          { code: 'loanNo', label: '借用单号', type: 'STRING' },
          { code: 'borrower', label: '借用人', type: 'STRING' },
          { code: 'auditNote', label: '审核意见', type: 'TEXT' },
          { code: 'tool', label: '借出明细 · 工器具', type: 'STRING', table: 'loanLine' },
          { code: 'qty', label: '借出明细 · 数量', type: 'INTEGER', table: 'loanLine' }
        ],
        childConcepts: [{ key: 'loanLine', concept: 'acme.tool.LoanLine', displayName: '借出明细' }]
      }
    );
    const options = async () => {
      (q('bind-field')!.querySelector('.el-select__wrapper') as HTMLElement).click();
      await flush();
      return [...document.querySelectorAll('.el-select-dropdown__item')]
        .filter((e) => (e as HTMLElement).offsetParent !== null)
        .map((e) => e.textContent!.trim());
    };
    await flush(400); // 设计器的结构树防抖 300ms 后才能按字段定位
    ws.value.designer.triggerActive('tool');
    await flush();
    const inTable = await options();
    expect(inTable.join()).toContain('（qty）');
    expect(inTable.join()).not.toContain('（loanNo）');
    document.body.click();
    ws.value.designer.triggerActive('loanNo');
    await flush();
    const master = await options();
    expect(master.join()).toContain('（borrower）');
    expect(master.join()).not.toContain('（qty）');
  });

  it('发布成功显示结果', async () => {
    const publish = vi.fn(async () => ({ changeSetId: 9, status: 'APPLIED', breaking: false, removedFields: [], addedFields: ['auditNote'] }));
    await mount({ saveDraft: vi.fn(async () => ({ type: 'FORM', issues: [], fieldRefs: [] })), check: vi.fn(), publish });
    q('publish')!.click();
    await flush(100);
    expect(publish).toHaveBeenCalledWith('acme.tool.loanForm', 2);
    expect(q('publish-result')!.textContent).toContain('已发布 v2');
  });
});
