/**
 * 8.5：fc-adapter 双向转换。LS → FC → LS 语义等价（纯转换 + 真实 form-create 设计器 setRule/getRule），单向边界被明确拒绝。
 */
import { afterEach, describe, expect, it } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import { FcDesigner, formCreate } from '@/vendor/form-create';
import { FcAdapterError, fromFcRule, normalizeForm, toFcRule } from '@/utils/lsform/fcAdapter';
import { lsDragRules, LS_MENUS } from '@/views/lsmom/form-designer/lsDragRules';
import type { FormSchema } from '@/types/lsView';
import { WIDGETS } from '@/types/lsView';

const loanForm: FormSchema = {
  viewType: 'FORM',
  formCode: 'acme.tool.loanForm',
  version: 2,
  conceptCode: 'acme.tool.LoanOrder',
  title: '工器具借还单',
  labelWidth: 110,
  layout: [
    {
      type: 'row',
      children: [
        { type: 'col', span: 12, children: [{ type: 'field', key: 'loanNo' }] },
        { type: 'col', span: 12, children: [{ type: 'field', key: 'borrower' }] }
      ]
    },
    {
      type: 'tabs',
      children: [
        {
          type: 'tab',
          title: '借用信息',
          children: [
            { type: 'field', key: 'purpose' },
            { type: 'field', key: 'toolRef' },
            { type: 'divider', title: '说明' },
            { type: 'text', text: '按实际借用填写' }
          ]
        },
        { type: 'tab', title: '审核', children: [{ type: 'group', title: '审核意见', children: [{ type: 'field', key: 'auditNote' }] }] }
      ]
    },
    {
      type: 'collapse',
      children: [
        {
          type: 'panel',
          title: '更多',
          collapsed: true,
          children: [
            { type: 'field', key: 'qtyNote' },
            { type: 'metricCard', metric: 'acme.tool.loanCount', title: '本月借出' }
          ]
        }
      ]
    },
    { type: 'subTable', key: 'loanLine' }
  ],
  fields: [
    {
      key: 'loanNo',
      label: '借用单号',
      widget: 'input',
      required: true,
      maxLength: 32,
      pattern: '^L-[0-9A-Z-]+$',
      patternMessage: '单号须以 L- 开头',
      span: 12
    },
    { key: 'borrower', label: '借用人', widget: 'userRef', required: true, placeholder: '选择借用人' },
    {
      key: 'purpose',
      label: '用途',
      widget: 'select',
      options: [
        { value: 'MAINTENANCE', label: '检修' },
        { value: 'OTHER', label: '其他' }
      ],
      default: 'MAINTENANCE'
    },
    {
      key: 'toolRef',
      label: '主要工器具',
      widget: 'assetRef',
      ref: { concept: 'acme.tool.Tool', labelField: 'name', filter: [{ field: 'orgId', op: 'eq', value: '$context.orgId' }] }
    },
    { key: 'auditNote', label: '审核意见', widget: 'textarea', readonly: true, help: '由审核人填写' },
    { key: 'qtyNote', label: '数量说明', widget: 'computed', computed: { expression: "string(size($model.loanLine)) + ' 项'" } },
    { key: 'secretNo', label: '内部编号', widget: 'input', hidden: true }
  ],
  subTables: [
    {
      key: 'loanLine',
      label: '借出明细',
      conceptCode: 'acme.tool.LoanLine',
      minRows: 1,
      maxRows: 20,
      fields: [
        { key: 'tool', label: '工器具', widget: 'input', required: true },
        { key: 'qty', label: '数量', widget: 'number', required: true, min: 1, max: 99 },
        { key: 'photo', label: '照片', widget: 'image', maxCount: 3 }
      ]
    }
  ],
  rules: [{ level: 'L1', when: "$model.purpose == 'OTHER'", assert: 'has($model.remark)', field: 'purpose', message: '用途为其他时必须填写备注' }],
  actions: [{ key: 'submit', label: '提交', operation: 'acme.tool.LoanOrder.submit', style: 'primary' }],
  nodePolicies: { deptAudit: { readonly: ['*'], editable: ['auditNote'] } },
  migration: { strategy: 'PROMPT' }
};

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
});

async function flush(ms = 50) {
  for (let i = 0; i < 6; i++) await nextTick();
  await new Promise((r) => setTimeout(r, ms));
}

describe('fc-adapter 纯转换', () => {
  it('LS → FC → LS 语义等价（含主子表、页签、折叠、未放置的隐藏字段、表单级属性）', () => {
    const { rule, options } = toFcRule(loanForm);
    expect(rule.find((r) => r.type === 'tableForm')!.props.columns).toHaveLength(3);
    expect(options.lsMeta!.nodePolicies).toEqual(loanForm.nodePolicies);
    expect(normalizeForm(fromFcRule(rule, options))).toEqual(normalizeForm(loanForm));
  });

  it('每个 MVP 控件都能往返', () => {
    const fields = WIDGETS.map((w) => ({ key: 'f' + w.charAt(0).toUpperCase() + w.slice(1), label: w, widget: w }));
    const s: FormSchema = {
      viewType: 'FORM',
      formCode: 'acme.demo.all',
      version: 1,
      conceptCode: 'acme.demo.All',
      layout: fields.map((f) => ({ type: 'field', key: f.key })),
      fields
    };
    const { rule, options } = toFcRule(s);
    expect(normalizeForm(fromFcRule(rule, options))).toEqual(normalizeForm(s));
  });

  it('设计器附加的内部属性不影响结果', () => {
    const { rule, options } = toFcRule(loanForm);
    const decorate = (rs: any[]) =>
      rs.forEach((r) => {
        if (typeof r !== 'object') return;
        Object.assign(r, { _fc_id: 'id_x', name: 'ref_x', display: true, hidden: false, effect: {}, computed: {} });
        if (r.children) decorate(r.children);
      });
    decorate(rule);
    expect(normalizeForm(fromFcRule(rule, options))).toEqual(normalizeForm(loanForm));
  });

  it('单向边界：事件、联动、远程数据、函数属性、未知组件被拒绝并定位', () => {
    const { rule, options } = toFcRule(loanForm);
    rule[0].children[0].children[0].on = { change: '$FNX:alert(1)' };
    rule.push({ type: 'input', field: 'x1', props: { formatter: '$FN:v=>v' } });
    rule.push({ type: 'elTransfer', children: [] });
    rule.push({ type: 'select', field: 'x2', effect: { fetch: { action: '/api/x' } } });
    try {
      fromFcRule(rule, options);
      expect.unreachable();
    } catch (e) {
      expect(e).toBeInstanceOf(FcAdapterError);
      const msgs = (e as FcAdapterError).issues.map((i) => i.message);
      expect(msgs).toEqual(
        expect.arrayContaining(['事件绑定（on）', '函数属性', '设计器组件「elTransfer」没有对应的 LS 节点', '远程数据（effect.fetch）'])
      );
    }
  });
});

describe('fc-adapter 与真实 form-create 设计器', () => {
  it('setRule(toFcRule) 后 getRule → fromFcRule 与原 Schema 语义等价', async () => {
    host = document.createElement('div');
    host.style.width = '1400px';
    host.style.height = '900px';
    document.body.appendChild(host);
    const designer = ref<any>();
    app = createApp({ setup: () => () => h(FcDesigner as any, { ref: designer, height: '880px', config: { showSaveBtn: false } }) });
    app.use(ElementPlus);
    app.use(FcDesigner as any);
    app.use(formCreate);
    app.mount(host);
    await flush(200);
    for (const m of LS_MENUS) designer.value.addMenu({ name: m.name, title: m.title, list: [] });
    designer.value.addComponent(lsDragRules());
    const { rule, options } = toFcRule(loanForm);
    designer.value.setRule(rule);
    designer.value.setOption(options);
    await flush(200);
    const back = fromFcRule(designer.value.getRule(), designer.value.getOption());
    expect(normalizeForm(back)).toEqual(normalizeForm(loanForm));
    // 画布上真实渲染了子表与字段
    expect(host.textContent).toContain('借出明细');
    expect(host.textContent).toContain('借用单号');
  });
});
