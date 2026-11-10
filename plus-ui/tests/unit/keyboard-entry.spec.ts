/**
 * modernize-ui-ux 4.5：表单与子表键盘录入。
 * Enter 跳到下一个字段；子表最后一行最后一格 Enter 新增一行并聚焦新行第一格；多行文本里 Enter 换行；
 * Ctrl/⌘+S 触发保存、Ctrl/⌘+Enter 触发提交；快捷键文字按平台显示。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import type { FormSchema } from '@/types/lsView';
import { hotkeyLabel, matchHotkey } from '@/utils/hotkeys';

const form: FormSchema = {
  viewType: 'FORM',
  formCode: 'acme.tool.loanForm',
  version: 1,
  conceptCode: 'acme.tool.LoanOrder',
  layout: [
    { type: 'field', key: 'loanNo' },
    { type: 'field', key: 'remark' },
    { type: 'field', key: 'borrower' },
    { type: 'subTable', key: 'loanLine' }
  ],
  fields: [
    { key: 'loanNo', label: '借用单号', widget: 'input' },
    { key: 'remark', label: '备注', widget: 'textarea' },
    { key: 'borrower', label: '借用人', widget: 'input' }
  ],
  subTables: [
    {
      key: 'loanLine',
      label: '借出明细',
      conceptCode: 'acme.tool.LoanLine',
      fields: [
        { key: 'tool', label: '工器具', widget: 'input' },
        { key: 'qty', label: '数量', widget: 'number', default: 1 }
      ]
    }
  ]
};

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

const press = (el: Element, key: string, init: KeyboardEventInit = {}) =>
  el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));

describe('键盘录入', () => {
  it('Enter 跳字段、末行末格新增行并聚焦新行第一格、多行文本不拦截、快捷键触发保存与提交', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const model = ref<Record<string, any>>({ loanLine: [{ tool: '扳手', qty: 2 }] });
    const events: string[] = [];
    const api = { refSearch: vi.fn(async () => []), upload: vi.fn(), dict: vi.fn(async () => []) };
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(SchemaForm as any, {
            schema: form,
            api,
            hotkeys: true,
            modelValue: model.value,
            'onUpdate:modelValue': (v: any) => (model.value = v),
            onSave: () => events.push('save'),
            onSubmit: () => events.push('submit')
          })
      })
    );
    app.use(ElementPlus).mount(host);
    await flush();
    const q = (id: string) => host!.querySelector(`[data-testid="${id}"]`) as HTMLElement;

    const loanNo = q('input-loanNo') as HTMLInputElement;
    loanNo.focus();
    press(loanNo, 'Enter');
    await flush();
    const remark = q('input-remark');
    expect(document.activeElement).toBe(remark);
    // 多行文本里 Enter 是换行，不跳走
    const ev = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    remark.dispatchEvent(ev);
    expect(ev.defaultPrevented).toBe(false);
    expect(document.activeElement).toBe(remark);

    // 子表最后一行最后一格 Enter：新增一行（带默认值），焦点到新行第一格
    const qty = q('cell-loanLine-0-qty').querySelector('input')!;
    qty.focus();
    press(qty, 'Enter');
    await flush();
    expect(model.value.loanLine).toHaveLength(2);
    expect(model.value.loanLine[1]).toEqual({ qty: 1 });
    expect(document.activeElement).toBe(q('cell-loanLine-1-tool').querySelector('input'));
    // 新行中间格 Enter：跳到同一行下一格，不再新增
    press(document.activeElement!, 'Enter');
    await flush();
    expect(model.value.loanLine).toHaveLength(2);
    expect(document.activeElement).toBe(q('cell-loanLine-1-qty').querySelector('input'));

    press(document.activeElement!, 's', { ctrlKey: true });
    press(document.activeElement!, 'Enter', { ctrlKey: true });
    await flush();
    expect(events).toEqual(['save', 'submit']);
    expect(q('form-hotkey-hints').textContent).toMatch(/保存草稿/);
  });

  it('组合键匹配与显示', () => {
    expect(matchHotkey(new KeyboardEvent('keydown', { key: 'S', ctrlKey: true }), 'mod+s')).toBe(true);
    expect(matchHotkey(new KeyboardEvent('keydown', { key: 's', metaKey: true }), 'mod+s')).toBe(true);
    expect(matchHotkey(new KeyboardEvent('keydown', { key: 's' }), 'mod+s')).toBe(false);
    expect(matchHotkey(new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, shiftKey: true }), 'mod+enter')).toBe(false);
    expect(hotkeyLabel('mod+enter')).toMatch(/^(Ctrl\+Enter|⌘Enter)$/);
  });
});

describe('引用搜索', () => {
  it('先发出的搜索晚返回时不覆盖后来的结果', async () => {
    const { default: RefSelect } = await import('@/components/SchemaForm/RefSelect.vue');
    let releaseFirst: (v: any) => void = () => undefined;
    const api = {
      refSearch: vi.fn((b: any) =>
        b.keyword === '' ? new Promise((r) => (releaseFirst = r)) : Promise.resolve([{ id: 2, label: '郑师傅' }])
      ),
      upload: vi.fn(),
      dict: vi.fn()
    };
    host = document.createElement('div');
    document.body.appendChild(host);
    const inst = ref<any>();
    app = createApp(defineComponent({ setup: () => () => h(RefSelect as any, { ref: inst, modelValue: null, refSpec: { concept: 'ls.core.Party' }, api }) }));
    app.use(ElementPlus).mount(host);
    const first = inst.value.search('');
    await inst.value.search('郑');
    releaseFirst([{ id: 9, label: '无关的人' }]);
    await first;
    expect(inst.value.options.map((o: any) => o.label)).toEqual(['郑师傅']);
  });
});
