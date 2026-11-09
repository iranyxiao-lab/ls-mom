/**
 * 8.4：SchemaForm / SchemaDetail / SchemaPrint / SchemaMobile 渲染器与 MVP 全部控件。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import SchemaForm from '@/components/SchemaForm/index.vue';
import SchemaDetail from '@/components/SchemaDetail/index.vue';
import SchemaPrint from '@/components/SchemaPrint/index.vue';
import SchemaMobile from '@/components/SchemaMobile/index.vue';
import { registerFormComponent } from '@/components/SchemaForm/registry';
import type { FormSchema, MobileSchema, PrintSchema, Widget } from '@/types/lsView';
import { WIDGETS } from '@/types/lsView';
import type { RendererApi } from '@/api/lsmom/form';
import { validateForm } from '@/utils/lsform/validate';
import { resolveFieldPolicy } from '@/utils/lsform/fieldPolicy';

const loanForm: FormSchema = {
  viewType: 'FORM',
  formCode: 'acme.tool.loanForm',
  version: 3,
  conceptCode: 'acme.tool.LoanOrder',
  title: '工器具借还单',
  layout: [
    {
      type: 'row',
      children: [
        { type: 'col', span: 12, children: [{ type: 'field', key: 'loanNo' }] },
        { type: 'col', span: 12, children: [{ type: 'field', key: 'borrower' }] }
      ]
    },
    {
      type: 'group',
      title: '借用信息',
      children: [
        { type: 'field', key: 'purpose' },
        { type: 'field', key: 'toolRef' },
        { type: 'field', key: 'remark' },
        { type: 'field', key: 'qtyNote' },
        { type: 'field', key: 'auditNote' }
      ]
    },
    { type: 'subTable', key: 'loanLine' }
  ],
  fields: [
    { key: 'loanNo', label: '借用单号', widget: 'input', required: true, pattern: '^L-[0-9A-Z-]+$', patternMessage: '单号须以 L- 开头' },
    { key: 'borrower', label: '借用人', widget: 'input', required: true },
    {
      key: 'purpose',
      label: '用途',
      widget: 'select',
      options: [
        { value: 'MAINTENANCE', label: '检修' },
        { value: 'OTHER', label: '其他' }
      ]
    },
    {
      key: 'toolRef',
      label: '主要工器具',
      widget: 'assetRef',
      ref: { concept: 'acme.tool.Tool', labelField: 'name', filter: [{ field: 'orgId', op: 'eq', value: '$context.orgId' }] }
    },
    { key: 'remark', label: '备注', widget: 'textarea', maxLength: 200 },
    { key: 'qtyNote', label: '数量说明', widget: 'computed', computed: { expression: "string(size($model.loanLine)) + ' 项'" } },
    { key: 'auditNote', label: '审核意见', widget: 'textarea', readonly: true }
  ],
  subTables: [
    {
      key: 'loanLine',
      label: '借出明细',
      conceptCode: 'acme.tool.LoanLine',
      minRows: 1,
      fields: [
        { key: 'tool', label: '工器具', widget: 'input', required: true },
        { key: 'qty', label: '数量', widget: 'number', required: true, min: 1, max: 99 }
      ]
    }
  ],
  rules: [
    {
      level: 'L1',
      when: "$model.purpose == 'OTHER'",
      assert: 'has($model.remark) && size($model.remark) > 0',
      field: 'remark',
      message: '用途为其他时必须填写备注'
    },
    { level: 'L0', assert: '!has($model.remark) || size($model.remark) < 50', message: '备注较长，建议精简' }
  ],
  actions: [{ key: 'submit', label: '提交', operation: 'acme.tool.LoanOrder.submit' }],
  nodePolicies: { deptAudit: { readonly: ['*'], editable: ['auditNote'], required: ['auditNote'] } }
};

function fakeApi(): RendererApi & { refSearch: ReturnType<typeof vi.fn> } {
  return {
    refSearch: vi.fn(async (b: any) =>
      b.ids
        ? b.ids.map((id: number) => ({ id, label: '工器具 #' + id }))
        : [
            { id: 7, label: '扭矩扳手' },
            { id: 8, label: '万用表' }
          ]
    ),
    upload: vi.fn(async (f: File) => ({ ossId: '101', fileName: f.name, url: 'blob:x' })),
    dict: vi.fn(async () => [
      { label: '是', value: 'Y' },
      { label: '否', value: 'N' }
    ])
  } as any;
}

let app: App | null = null;
let host: HTMLElement | null = null;

async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await new Promise((r) => setTimeout(r, 30));
}

/** 用父组件持有 v-model 状态挂载。 */
async function mount(comp: Component, props: Record<string, unknown>, initial: Record<string, any> = {}) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const model = ref<Record<string, any>>(initial);
  const inst = ref<any>();
  const events: any[] = [];
  const Host = defineComponent({
    setup: () => () =>
      h(comp as any, {
        ...props,
        ref: inst,
        modelValue: model.value,
        'onUpdate:modelValue': (v: Record<string, any>) => (model.value = v),
        onAction: (p: unknown) => events.push(p)
      })
  });
  app = createApp(Host);
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return { el: host, model, inst, events };
}

afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
  try {
    localStorage.clear();
  } catch {
    /* ignore */
  }
});

const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

async function type(el: HTMLElement, id: string, text: string) {
  const box = q(el, id)!;
  const input = (box.tagName === 'INPUT' || box.tagName === 'TEXTAREA' ? box : box.querySelector('input,textarea')) as HTMLInputElement;
  input.value = text;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
}

describe('SchemaForm', () => {
  it('MVP 全部控件都能渲染（编辑与只读）', async () => {
    registerFormComponent(
      'acme.widget.signaturePad',
      '1.0.0',
      defineComponent({ props: ['modelValue'], setup: () => () => h('div', { 'data-testid': 'sig-pad' }, '签名板') })
    );
    const fields = WIDGETS.map((w: Widget) => ({
      key: 'f' + w.charAt(0).toUpperCase() + w.slice(1),
      label: w,
      widget: w,
      ...(w === 'select' || w === 'radio' || w === 'checkbox' || w === 'cascader'
        ? { options: [{ value: 'A', label: '甲', children: w === 'cascader' ? [{ value: 'A1', label: '甲一' }] : undefined }] }
        : {}),
      ...(w === 'dict' ? { dictType: 'sys_yes_no' } : {}),
      ...(['assetRef', 'materialRef', 'pointRef', 'conceptRef', 'remoteSelect'].includes(w) ? { ref: { concept: 'acme.tool.Tool' } } : {}),
      ...(w === 'computed' || w === 'expression' ? { computed: { expression: '1 + 1' } } : {}),
      ...(w === 'registered' ? { component: { code: 'acme.widget.signaturePad', version: '1.0.0' } } : {})
    }));
    const schema: FormSchema = {
      viewType: 'FORM',
      formCode: 'acme.demo.allWidgets',
      version: 1,
      conceptCode: 'acme.demo.All',
      layout: [
        { type: 'tabs', children: [{ type: 'tab', title: '全部', children: fields.map((f) => ({ type: 'field' as const, key: f.key })) }] },
        { type: 'collapse', children: [{ type: 'panel', title: '说明', children: [{ type: 'text', text: '说明文字' }, { type: 'divider' }] }] },
        { type: 'metricCard', metric: 'acme.demo.count', title: '指标' },
        { type: 'relatedList', concept: 'acme.demo.Other', via: 'parent', title: '关联' }
      ],
      fields: fields as any
    };
    const api = fakeApi();
    const { el, model } = await mount(SchemaForm, { schema, api, metrics: { 'acme.demo.count': 42 } });
    for (const f of fields) expect(q(el, 'field-' + f.key), f.widget).not.toBeNull();
    expect(q(el, 'sig-pad')).not.toBeNull();
    expect(q(el, 'metric-acme.demo.count')!.textContent).toContain('42');
    expect(model.value.fComputed).toBe(2);
    expect(api.dict).toHaveBeenCalledWith('sys_yes_no');
    app!.unmount();
    host!.remove();
    const ro = await mount(SchemaForm, { schema, api, readonly: true }, { fSwitch: true, fSelect: 'A', fCheckbox: ['A'] });
    expect(q(ro.el, 'text-fSwitch')!.textContent).toContain('是');
    expect(q(ro.el, 'text-fSelect')!.textContent).toContain('甲');
    expect(ro.el.querySelector('input:not([disabled]):not([readonly]), textarea:not([disabled])')).toBeNull();
  });

  it('客户端校验与服务端规则一致：正则、子表行数与范围、L1 条件规则、L0 提示', () => {
    const access = resolveFieldPolicy(loanForm);
    let r = validateForm(loanForm, { loanNo: 'X-1', borrower: '张三', loanLine: [] }, access);
    expect(r.errors).toMatchObject({ loanNo: '单号须以 L- 开头', loanLine: '借出明细至少 1 行' });
    r = validateForm(loanForm, { loanNo: 'L-1', borrower: '张三', loanLine: [{ tool: '扳手', qty: 120 }] }, access);
    expect(r.errors).toEqual({ 'loanLine[0].qty': '第 1 行：数量不能大于 99' });
    r = validateForm(loanForm, { loanNo: 'L-1', borrower: '张三', purpose: 'OTHER', loanLine: [{ tool: '扳手', qty: 1 }] }, access);
    expect(r.errors).toEqual({ remark: '用途为其他时必须填写备注' });
    r = validateForm(
      loanForm,
      { loanNo: 'L-1', borrower: '张三', purpose: 'OTHER', remark: '长'.repeat(60), loanLine: [{ tool: '扳手', qty: 1 }] },
      access
    );
    expect(r.errors).toEqual({});
    expect(r.warnings).toEqual(['备注较长，建议精简']);
  });

  it('子表增删行、计算字段随之更新，动作提交报文带表单版本且不含只读字段', async () => {
    const api = fakeApi();
    const { el, model, events } = await mount(SchemaForm, { schema: loanForm, api, orgId: 3 }, { auditNote: '旧意见' });
    expect(model.value.qtyNote).toBe('0 项');
    q(el, 'add-row-loanLine')!.click();
    await flush();
    q(el, 'add-row-loanLine')!.click();
    await flush();
    expect(model.value.loanLine).toHaveLength(2);
    expect(model.value.qtyNote).toBe('2 项');
    q(el, 'remove-row-loanLine-1')!.click();
    await flush();
    expect(model.value.qtyNote).toBe('1 项');
    await type(el, 'input-loanNo', 'L-9');
    await type(el, 'input-borrower', '张三');
    q(el, 'action-submit')!.click();
    await flush();
    expect(events).toHaveLength(0); // 子表必填未填
    expect(el.textContent).toContain('第 1 行：工器具必填');
    model.value = { ...model.value, loanLine: [{ tool: '扳手', qty: 2 }] };
    await flush();
    q(el, 'action-submit')!.click();
    await flush();
    expect(events).toHaveLength(1);
    expect(events[0].action.operation).toBe('acme.tool.LoanOrder.submit');
    expect(events[0].model).toMatchObject({ loanNo: 'L-9', borrower: '张三', _form: { code: 'acme.tool.loanForm', version: 3 } });
    expect(events[0].model).not.toHaveProperty('auditNote');
    expect(events[0].model).not.toHaveProperty('qtyNote');
  });

  it('引用控件把过滤与组织上下文交给服务端搜索并回显名称', async () => {
    const api = fakeApi();
    const { el } = await mount(SchemaForm, { schema: loanForm, api, orgId: 3 }, { toolRef: 7 });
    expect(api.refSearch).toHaveBeenCalledWith(expect.objectContaining({ ids: [7], orgId: 3, ref: loanForm.fields[3].ref }));
    const input = q(el, 'field-toolRef')!.querySelector('input')!;
    input.dispatchEvent(new Event('focus'));
    input.value = '扳手';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await new Promise((r) => setTimeout(r, 400));
    await flush();
    expect(api.refSearch).toHaveBeenCalledWith(expect.objectContaining({ keyword: '扳手', orgId: 3 }));
  });

  it('流程节点策略：readonly * + editable auditNote 时只有审核意见可编辑', async () => {
    const { el } = await mount(
      SchemaForm,
      { schema: loanForm, api: fakeApi(), node: 'deptAudit' },
      { loanNo: 'L-1', borrower: '张三', loanLine: [{ tool: '扳手', qty: 1 }] }
    );
    expect(q(el, 'input-auditNote')).not.toBeNull();
    expect(q(el, 'input-loanNo')).toBeNull();
    expect(q(el, 'text-loanNo')!.textContent).toContain('L-1');
    expect(q(el, 'add-row-loanLine')).toBeNull();
    expect(q(el, 'field-auditNote')!.className).toContain('is-required');
  });
});

describe('SchemaDetail / SchemaPrint / SchemaMobile', () => {
  it('历史记录按冻结版本只读显示并提示版本', async () => {
    const { el } = await mount(SchemaDetail, {
      schema: loanForm,
      record: { loanNo: 'L-1', purpose: 'OTHER', loanLine: [{ tool: '扳手', qty: 2 }] },
      frozenVersion: 3,
      latestVersion: 4,
      api: fakeApi()
    });
    expect(q(el, 'frozen-hint')!.textContent).toContain('v3');
    expect(q(el, 'text-purpose')!.textContent).toContain('其他');
    expect(el.querySelector('textarea:not([disabled])')).toBeNull();
  });

  it('DetailSchema 分组与子表', async () => {
    const { el } = await mount(SchemaDetail, {
      schema: {
        viewType: 'DETAIL',
        formCode: 'acme.tool.loanDetail',
        version: 1,
        conceptCode: 'acme.tool.LoanOrder',
        groups: [{ title: '基本信息', fields: ['loanNo', 'urgent'] }],
        subTables: ['acme.tool.LoanLine']
      },
      record: { loanNo: 'L-1', urgent: 'Y', urgentLabel: '是', loanLine: [{ id: 1, tool: '扳手', qty: 2 }] },
      labels: { loanNo: '借用单号', urgent: '是否加急', loanLine: '借出明细' }
    });
    expect(el.textContent).toContain('借用单号').toString();
    expect(el.textContent).toContain('是');
    expect(el.textContent).toContain('借出明细');
    expect(el.textContent).toContain('扳手');
  });

  it('打印视图含页眉、分栏、明细、签字区与模板版本', async () => {
    const schema: PrintSchema = {
      viewType: 'PRINT',
      formCode: 'acme.tool.loanPrint',
      version: 2,
      conceptCode: 'acme.tool.LoanOrder',
      templateVersion: '1.2.0',
      header: { title: '工器具借还单' },
      sections: [
        { fields: ['loanNo', 'borrower'], columns: 2 },
        { title: '明细', fields: [], subTable: 'acme.tool.LoanLine' }
      ],
      signatures: [{ label: '借用人' }, { label: '审核人' }],
      footer: { text: '一式两份' }
    };
    const { el } = await mount(SchemaPrint, {
      schema,
      record: { loanNo: 'L-1', borrower: '张三', loanLine: [{ tool: '扳手', qty: 2 }] },
      labels: { loanNo: '借用单号' }
    });
    const t = q(el, 'schema-print')!.textContent!;
    for (const s of ['工器具借还单', '借用单号', 'L-1', '扳手', '借用人', '审核人', '一式两份', '1.2.0']) expect(t).toContain(s);
  });

  it('移动端只显示精简字段，提交仍按完整表单校验，离线草稿可恢复', async () => {
    const mobile: MobileSchema = {
      viewType: 'MOBILE',
      formCode: 'acme.tool.loanMobile',
      version: 1,
      conceptCode: 'acme.tool.LoanOrder',
      fields: ['loanNo', 'borrower'],
      offlineDraft: true,
      actions: [{ key: 'submit', label: '提交', operation: 'acme.tool.LoanOrder.submit' }]
    };
    const m = await mount(SchemaMobile, { schema: mobile, form: loanForm, api: fakeApi() }, {});
    expect(q(m.el, 'field-loanNo')).not.toBeNull();
    expect(q(m.el, 'field-purpose')).toBeNull();
    expect(q(m.el, 'subtable-loanLine')).toBeNull();
    await type(m.el, 'input-loanNo', 'L-5');
    q(m.el, 'save-draft')!.click();
    q(m.el, 'm-action-submit')!.click();
    await flush();
    expect(m.events[0].errors).toMatchObject({ borrower: '借用人必填', loanLine: '借出明细至少 1 行' });
    expect(q(m.el, 'hidden-errors')!.textContent).toContain('借出明细至少 1 行');
    app!.unmount();
    host!.remove();
    const again = await mount(SchemaMobile, { schema: mobile, form: loanForm, api: fakeApi() }, {});
    expect(q(again.el, 'draft-restored')).not.toBeNull();
    expect(again.model.value.loanNo).toBe('L-5');
  });
});
