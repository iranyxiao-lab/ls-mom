/**
 * modernize-ui-ux 4.7：引用字段扫码。
 * 二维码内容取编码；扫码枪识别（快速连续输入以 Enter 结束，人手输入不误判）；引用字段扫到编码后在服务端精确查找并选中，
 * 找不到时在字段下显示原因；摄像头扫码对话框识别到内容后关闭摄像头并返回。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import ScanDialog from '@/components/SchemaForm/ScanDialog.vue';
import type { FormSchema } from '@/types/lsView';
import { createWedgeDetector, normalizeCode } from '@/utils/scan/scanCode';

const stop = vi.fn();
vi.mock('@zxing/browser', () => ({
  BrowserMultiFormatReader: class {
    async decodeFromConstraints(_c: unknown, _v: unknown, cb: (r: any) => void) {
      setTimeout(() => cb({ getText: () => 'https://mom.example.com/asset/P-101' }), 20);
      return { stop };
    }
  }
}));

describe('scanCode', () => {
  it('从二维码内容取编码', () => {
    expect(normalizeCode(' P-101 ')).toBe('P-101');
    expect(normalizeCode('https://x.cn/asset/P-101')).toBe('P-101');
    expect(normalizeCode('https://x.cn/a?code=EQ%2F9')).toBe('EQ/9');
    expect(normalizeCode('')).toBe('');
  });
  it('扫码枪：快速输入以 Enter 结束才识别，人手输入、太短、组合键不识别', () => {
    const d = createWedgeDetector();
    const type = (s: string, gap: number, t0 = 0) => {
      let t = t0;
      for (const c of s) d.feed({ key: c, ctrlKey: false, metaKey: false, altKey: false }, (t += gap));
      return d.feed({ key: 'Enter', ctrlKey: false, metaKey: false, altKey: false }, t + gap);
    };
    expect(type('P-101', 8)).toBe('P-101');
    expect(type('P-101', 150, 1000)).toBeNull();
    expect(type('P1', 8, 5000)).toBeNull();
    // 先慢慢敲两个字再扫：只取扫码部分
    d.feed({ key: 'x', ctrlKey: false, metaKey: false, altKey: false }, 9000);
    expect(type('EQ-77', 5, 9300)).toBe('EQ-77');
    d.feed({ key: 'v', ctrlKey: true, metaKey: false, altKey: false }, 10000);
    expect(d.feed({ key: 'Enter', ctrlKey: false, metaKey: false, altKey: false }, 10001)).toBeNull();
  });
});

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-message').forEach((e) => e.remove());
});
async function flush(ms = 30) {
  for (let i = 0; i < 6; i++) {
    await Promise.resolve();
    await nextTick();
  }
  await new Promise((r) => setTimeout(r, ms));
}

const form: FormSchema = {
  viewType: 'FORM',
  formCode: 'ls.asset.defectForm',
  version: 1,
  conceptCode: 'ls.asset.Defect',
  layout: [
    { type: 'field', key: 'asset' },
    { type: 'field', key: 'reporter' }
  ],
  fields: [
    { key: 'asset', label: '设备', widget: 'assetRef', ref: { concept: 'ls.asset.Asset' } },
    { key: 'reporter', label: '登记人', widget: 'userRef' }
  ]
};

function mount() {
  host = document.createElement('div');
  document.body.appendChild(host);
  const model = ref<Record<string, any>>({});
  const api = {
    refSearch: vi.fn(async () => []),
    refScan: vi.fn(async (b: any) => {
      if (b.code === 'P-101') return { id: 101, label: 'P-101 一号泵' };
      throw Object.assign(new Error(`没有找到编码为「${b.code}」的设备，或你无权使用`), { code: 'NOT_FOUND' });
    }),
    upload: vi.fn(),
    dict: vi.fn(async () => [])
  };
  app = createApp(
    defineComponent({
      setup: () => () => h(SchemaForm as any, { schema: form, api, modelValue: model.value, 'onUpdate:modelValue': (v: any) => (model.value = v) })
    })
  );
  app.use(ElementPlus).mount(host);
  return { model, el: host, api };
}

function wedgeType(input: HTMLElement, text: string) {
  for (const c of text) input.dispatchEvent(new KeyboardEvent('keydown', { key: c, bubbles: true, cancelable: true }));
  const enter = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
  input.dispatchEvent(enter);
  return enter;
}

describe('引用字段扫码', () => {
  it('扫码枪在设备字段里扫到编码：服务端精确查找并选中；人员字段没有扫码', async () => {
    const { model, el, api } = mount();
    await flush();
    expect(el.querySelector('[data-testid="scan-btn-ls.asset.Asset"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="scan-btn-ls.core.Party"]')).toBeNull();
    const input = el.querySelector('[data-field-key="asset"] input') as HTMLElement;
    input.focus();
    const enter = wedgeType(input, 'P-101');
    expect(enter.defaultPrevented).toBe(true);
    await flush();
    expect(api.refScan).toHaveBeenCalledWith(expect.objectContaining({ code: 'P-101', ref: { concept: 'ls.asset.Asset' } }));
    expect(model.value.asset).toBe(101);
    expect(el.querySelector('[data-field-key="asset"]')!.textContent).not.toContain('没有找到');
  });

  it('扫到不存在或无权的编码：字段下显示服务端给的原因', async () => {
    const { model, el } = mount();
    await flush();
    const input = el.querySelector('[data-field-key="asset"] input') as HTMLElement;
    input.focus();
    wedgeType(input, 'NOPE-9');
    await flush(150);
    expect(model.value.asset ?? null).toBeNull();
    expect(el.querySelector('[data-field-key="asset"]')!.textContent).toContain('没有找到编码为「NOPE-9」的设备，或你无权使用');
  });

  it('摄像头扫码：识别到二维码后关闭摄像头并返回内容', async () => {
    host = document.createElement('div');
    document.body.appendChild(host);
    const got: string[] = [];
    const open = ref(true);
    Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia: vi.fn() }, configurable: true });
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(ScanDialog as any, {
            modelValue: open.value,
            'onUpdate:modelValue': (v: boolean) => (open.value = v),
            onScanned: (t: string) => got.push(t)
          })
      })
    );
    app.use(ElementPlus).mount(host);
    await flush(600);
    expect(got).toEqual(['https://mom.example.com/asset/P-101']);
    expect(stop).toHaveBeenCalled();
    expect(open.value).toBe(false);
  });
});
