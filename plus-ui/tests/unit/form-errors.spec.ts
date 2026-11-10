/**
 * modernize-ui-ux 4.4：服务端字段错误映射、顶部汇总可点击定位、子表单元格高亮、失焦校验、提交失败滚动到第一个错误。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import type { FormSchema } from '@/types/lsView';
import { mapServerErrors, summaryItems } from '@/utils/lsform/serverErrors';

const form: FormSchema = {
  viewType: 'FORM',
  formCode: 'acme.tool.loanForm',
  version: 1,
  conceptCode: 'acme.tool.LoanOrder',
  layout: [
    { type: 'field', key: 'loanNo' },
    { type: 'field', key: 'borrower' },
    { type: 'subTable', key: 'loanLine' }
  ],
  fields: [
    { key: 'loanNo', label: '借用单号', widget: 'input', required: true },
    { key: 'borrower', label: '借用人', widget: 'input', required: true }
  ],
  subTables: [
    {
      key: 'loanLine',
      label: '借出明细',
      conceptCode: 'acme.tool.LoanLine',
      fields: [
        { key: 'tool', label: '工器具', widget: 'input', required: true },
        { key: 'qty', label: '数量', widget: 'number', required: true }
      ]
    }
  ]
};

const serverError = Object.assign(new Error('输入校验失败'), {
  errorCode: 'VALIDATION_FAILED',
  details: {
    fields: { 'loanLine[2].tool': '第 3 行：工器具最多 100 个字符' },
    errors: [{ key: 'loanLine[2].tool', field: 'loanLine.tool', table: 'loanLine', rowIndex: 2, code: 'MAX_LENGTH', message: '工器具最多 100 个字符', fix: '缩短到 100 个字符以内' }]
  }
});

describe('serverErrors', () => {
  it('统一结构映射为前端键，汇总按布局顺序并带子表名、行与列名', () => {
    const m = mapServerErrors(serverError)!;
    expect(m.errors).toEqual({ 'loanLine[2].tool': '第 3 行：工器具最多 100 个字符' });
    expect(m.fixes['loanLine[2].tool']).toBe('缩短到 100 个字符以内');
    // 只有旧格式 fields 时也能映射
    expect(mapServerErrors({ details: { fields: { borrower: '借用人必填' } } })!.errors).toEqual({ borrower: '借用人必填' });
    expect(mapServerErrors(new Error('网络错误'))).toBeNull();
    const items = summaryItems(form, { 'loanLine[2].tool': 'x', borrower: 'y', 'loanLine[0].qty': 'z' });
    expect(items.map((i) => i.key)).toEqual(['borrower', 'loanLine[0].qty', 'loanLine[2].tool']);
    expect(items[2].label).toBe('借出明细 · 工器具');
  });
});

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
});

async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await new Promise((r) => setTimeout(r, 30));
}

function mount(initial: Record<string, any>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const model = ref<Record<string, any>>(initial);
  const inst = ref<any>();
  const api = { refSearch: vi.fn(async () => []), upload: vi.fn(), dict: vi.fn(async () => []) };
  app = createApp(
    defineComponent({
      setup: () => () =>
        h('div', { style: 'height:300px;overflow:auto', id: 'scroller' }, [
          h('div', { style: 'height:900px' }),
          h(SchemaForm as any, { schema: form, api, ref: inst, modelValue: model.value, 'onUpdate:modelValue': (v: any) => (model.value = v) })
        ])
    })
  );
  app.use(ElementPlus).mount(host);
  return { model, inst, el: host };
}

describe('SchemaForm 错误定位', () => {
  it('服务端子表第 3 行错误：单元格高亮、汇总可点击、自动聚焦到该单元格', async () => {
    const lines = [1, 2, 3].map((i) => ({ tool: '扳手' + i, qty: 1 }));
    const { inst, el } = mount({ loanNo: 'L-1', borrower: '张三', loanLine: lines });
    await flush();
    expect(inst.value.setServerErrors(serverError)).toBe(true);
    await flush();
    const cell = el.querySelector('[data-testid="cell-loanLine-2-tool"]') as HTMLElement;
    expect(cell.className).toContain('is-error');
    expect(cell.textContent).toContain('第 3 行：工器具最多 100 个字符');
    expect(document.activeElement && cell.contains(document.activeElement)).toBe(true);
    const items = el.querySelectorAll('[data-testid="error-summary-item"]');
    expect(items).toHaveLength(1);
    expect(items[0].textContent).toContain('借出明细 · 工器具');
    expect(items[0].textContent).toContain('缩短到 100 个字符以内');

    // 焦点移开后点汇总项回到该单元格
    (el.querySelector('[data-testid="input-borrower"]') as HTMLInputElement).focus();
    (items[0] as HTMLElement).click();
    await flush();
    expect(cell.contains(document.activeElement)).toBe(true);

    // 改动该单元格后错误消失
    const input = cell.querySelector('input') as HTMLInputElement;
    input.value = '短名称';
    input.dispatchEvent(new Event('input'));
    await flush();
    expect(cell.className).not.toContain('is-error');
  });

  it('失焦时就近显示错误；提交校验失败显示汇总并聚焦第一个错误', async () => {
    const { inst, el } = mount({ loanLine: [] });
    await flush();
    const loanNo = el.querySelector('[data-testid="input-loanNo"]') as HTMLInputElement;
    // 无头页面不一定持有窗口焦点，直接派发失焦事件
    loanNo.dispatchEvent(new FocusEvent('focusout', { bubbles: true }));
    await flush();
    // Element Plus 的字段错误有约 100ms 的防抖
    await new Promise((r) => setTimeout(r, 300));
    expect(el.querySelector('[data-testid="field-loanNo"]')?.textContent).toContain('借用单号必填');
    expect(el.querySelector('[data-testid="error-summary"]'), '失焦校验不弹汇总').toBeNull();

    const r = inst.value.validate();
    await flush();
    expect(Object.keys(r.errors)).toEqual(['loanNo', 'borrower']);
    expect(el.querySelectorAll('[data-testid="error-summary-item"]')).toHaveLength(2);
    expect(document.activeElement).toBe(loanNo);
  });
});
