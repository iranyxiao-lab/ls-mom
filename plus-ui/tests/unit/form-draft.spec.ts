/**
 * modernize-ui-ux 4.3：表单草稿自动保存与恢复。
 * 内容摘要与键顺序无关；改动后 3 秒写本机与服务端，内容不变不重复写；重新打开时提示恢复，恢复后内容回到表单；提交后删除。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import type { FormSchema } from '@/types/lsView';
import { contentHash, draftTime, hasContent, newer } from '@/utils/lsform/draftModel';
import { localDrafts } from '@/utils/lsform/draftStore';
import { currentUserId } from '@/utils/currentUser';

const form: FormSchema = {
  viewType: 'FORM',
  formCode: 'acme.tool.loanForm',
  version: 1,
  conceptCode: 'acme.tool.LoanOrder',
  layout: [
    { type: 'field', key: 'borrower' },
    { type: 'field', key: 'remark' }
  ],
  fields: [
    { key: 'borrower', label: '借用人', widget: 'input' },
    { key: 'remark', label: '备注', widget: 'textarea' }
  ]
};

describe('draftModel', () => {
  it('摘要与键顺序无关、忽略内部键；内容变化摘要变化', () => {
    expect(contentHash({ a: 1, b: [1, { c: 2, d: 3 }] })).toBe(contentHash({ b: [1, { d: 3, c: 2 }], a: 1, _form: { code: 'x' } }));
    expect(contentHash({ a: 1 })).not.toBe(contentHash({ a: 2 }));
    expect(hasContent({ a: '', b: [], _x: 1 })).toBe(false);
    expect(hasContent({ a: '张三' })).toBe(true);
    expect(newer({ content: {}, hash: 'a', savedAt: 1 }, { content: {}, hash: 'b', savedAt: 2 })?.hash).toBe('b');
    expect(draftTime(new Date(2026, 10, 10, 9, 5).getTime(), new Date(2026, 10, 10, 18).getTime())).toBe('今天 09:05');
  });
});

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
});

async function flush() {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await new Promise((r) => setTimeout(r, 30));
}

function mountForm(api: any, key: string) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const model = ref<Record<string, any>>({});
  const inst = ref<any>();
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(SchemaForm as any, {
          schema: form,
          api,
          draftKey: key,
          ref: inst,
          modelValue: model.value,
          'onUpdate:modelValue': (v: any) => (model.value = v)
        })
    })
  );
  app.use(ElementPlus).mount(host);
  return { model, inst, el: host };
}

async function type(el: HTMLElement, id: string, text: string) {
  const input = el.querySelector(`[data-testid="${id}"]`) as HTMLInputElement;
  input.value = text;
  input.dispatchEvent(new Event('input'));
  await flush();
}

describe('SchemaForm 草稿', () => {
  it('改动后 3 秒自动保存、内容不变不重复写；重新打开提示恢复；提交后删除', async () => {
    currentUserId.value = 'u' + Date.now();
    const key = 'acme.tool.LoanOrder:new';
    const server: Record<string, any> = {};
    const api = {
      refSearch: vi.fn(async () => []),
      upload: vi.fn(),
      dict: vi.fn(async () => []),
      draftGet: vi.fn(async (k: string) => server[k] ?? null),
      draftSave: vi.fn(async (k: string, b: any) => (server[k] = { ...b, updatedAt: new Date().toISOString() })),
      draftDelete: vi.fn(async (k: string) => delete server[k])
    };
    const a = mountForm(api, key);
    await flush();
    expect(a.el.querySelector('[data-testid="draft-found"]')).toBeNull();

    await type(a.el, 'input-borrower', '张三');
    await type(a.el, 'input-remark', '借两天');
    expect(api.draftSave).not.toHaveBeenCalled();
    await new Promise((r) => setTimeout(r, 3300));
    expect(api.draftSave).toHaveBeenCalledTimes(1);
    expect(api.draftSave.mock.calls[0][1].content).toEqual({ borrower: '张三', remark: '借两天' });
    expect(a.el.querySelector('[data-testid="draft-saved"]')?.textContent).toContain('草稿已自动保存');
    const local = await localDrafts.get(`${currentUserId.value}:${key}`);
    expect(local?.content).toEqual({ borrower: '张三', remark: '借两天' });

    // 改回相同内容不重复写
    await type(a.el, 'input-remark', '借两天x');
    await type(a.el, 'input-remark', '借两天');
    await new Promise((r) => setTimeout(r, 3300));
    expect(api.draftSave).toHaveBeenCalledTimes(1);
    app!.unmount();
    host!.remove();

    // 重新打开（服务端草稿不可用时用本机草稿）：提示恢复
    api.draftGet.mockImplementation(async () => {
      throw new Error('offline');
    });
    const b = mountForm(api, key);
    await flush();
    expect(b.el.querySelector('[data-testid="draft-found"]')?.textContent).toContain('发现未提交的草稿');
    (b.el.querySelector('[data-testid="draft-restore"]') as HTMLElement).click();
    await flush();
    expect(b.model.value).toMatchObject({ borrower: '张三', remark: '借两天' });

    await b.inst.value.clearDraft();
    expect(api.draftDelete).toHaveBeenCalledWith(key);
    expect(await localDrafts.get(`${currentUserId.value}:${key}`)).toBeNull();
  }, 20_000);

  it('不同用户看不到本机草稿；丢弃后不再提示', async () => {
    const key = 'acme.tool.LoanOrder:7';
    await localDrafts.put('userA:' + key, { content: { borrower: '甲' }, hash: contentHash({ borrower: '甲' }), savedAt: Date.now() });
    const api = { refSearch: vi.fn(async () => []), upload: vi.fn(), dict: vi.fn(async () => []) };
    currentUserId.value = 'userB';
    const b = mountForm(api, key);
    await flush();
    expect(b.el.querySelector('[data-testid="draft-found"]')).toBeNull();
    app!.unmount();
    host!.remove();

    currentUserId.value = 'userA';
    const a = mountForm(api, key);
    await flush();
    expect(a.el.querySelector('[data-testid="draft-found"]')).not.toBeNull();
    (a.el.querySelector('[data-testid="draft-discard"]') as HTMLElement).click();
    await flush();
    expect(a.el.querySelector('[data-testid="draft-found"]')).toBeNull();
    expect(await localDrafts.get('userA:' + key)).toBeNull();
  });
});
