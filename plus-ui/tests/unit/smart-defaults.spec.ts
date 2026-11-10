/**
 * modernize-ui-ux 4.2：自动带入与记住上次填写。
 * 从设备报缺陷：设备、位置来自设备，登记人、登记时间来自当前上下文；严重度为上次填写；已有值不被覆盖；改动后不再标记来源。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, defineComponent, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import SchemaForm from '@/components/SchemaForm/index.vue';
import type { FormSchema } from '@/types/lsView';
import { rememberValues, smartDefaults } from '@/utils/lsform/smartDefaults';

const defectForm: FormSchema = {
  viewType: 'FORM',
  formCode: 'ls.asset.defectForm',
  version: 2,
  conceptCode: 'ls.asset.Defect',
  layout: ['asset', 'location', 'reporter', 'reportedAt', 'foundOn', 'shift', 'title', 'severity'].map((key) => ({ type: 'field' as const, key })),
  fields: [
    { key: 'asset', label: '设备', widget: 'assetRef', defaultFrom: '$source.id', ref: { concept: 'ls.asset.Asset' } },
    { key: 'location', label: '位置', widget: 'input', defaultFrom: '$source.location' },
    { key: 'reporter', label: '登记人', widget: 'userRef', defaultFrom: '$user' },
    { key: 'reportedAt', label: '登记时间', widget: 'datetime', defaultFrom: '$now' },
    { key: 'foundOn', label: '发现日期', widget: 'date', defaultFrom: '$now' },
    { key: 'shift', label: '班次', widget: 'select', defaultFrom: '$shift', remember: true, options: [{ value: 'DAY', label: '早班' }] },
    { key: 'title', label: '缺陷描述', widget: 'input', required: true },
    { key: 'severity', label: '严重度', widget: 'select', remember: true, default: 'MEDIUM', options: [{ value: 'HIGH', label: '高' }] }
  ]
};
const ctx = { partyId: 31, orgId: 5, shift: 'EVENING', now: '2026-11-10T17:30:00' };
const pump = { id: 88, name: '一号泵', location: '一车间 A 区' };

describe('smartDefaults', () => {
  it('优先级：来源对象 > 上次填写 > 当前上下文 > 字段默认值', () => {
    const r = smartDefaults(defectForm, {}, { ...ctx, source: pump, remembered: { shift: 'DAY' } });
    expect(r.values).toEqual({
      asset: 88,
      location: '一车间 A 区',
      reporter: 31,
      reportedAt: '2026-11-10T17:30:00',
      foundOn: '2026-11-10',
      shift: 'DAY',
      severity: 'MEDIUM'
    });
    expect(r.origin).toMatchObject({ asset: 'source', reporter: 'system', shift: 'remembered', severity: 'static' });
  });

  it('不覆盖已有值；没有来源对象时不带入来源字段', () => {
    const r = smartDefaults(defectForm, { reporter: 99, title: 'x' }, ctx);
    expect(r.values.reporter).toBeUndefined();
    expect(r.values.asset).toBeUndefined();
    expect(r.values.shift).toBe('EVENING');
    expect(r.origin.shift).toBe('system');
  });

  it('只记住标了 remember 且有值的字段', () => {
    expect(rememberValues(defectForm, { severity: 'HIGH', shift: '', title: 'x', reporter: 1 })).toEqual({ severity: 'HIGH' });
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

describe('SchemaForm 预填', () => {
  it('新单据按上下文预填并标出来源，改动后清除标记，提交后记住', async () => {
    const api = {
      refSearch: vi.fn(async (b: any) => (b.ids ?? []).map((id: number) => ({ id, label: '#' + id }))),
      upload: vi.fn(),
      dict: vi.fn(async () => []),
      formContext: vi.fn(async () => ({ ...ctx, remembered: { severity: 'HIGH' } })),
      remember: vi.fn(async (_f: string, v: any) => v)
    };
    host = document.createElement('div');
    document.body.appendChild(host);
    const model = ref<Record<string, any>>({});
    const inst = ref<any>();
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(SchemaForm as any, {
            schema: defectForm,
            api,
            source: pump,
            ref: inst,
            modelValue: model.value,
            'onUpdate:modelValue': (v: any) => (model.value = v)
          })
      })
    );
    app.use(ElementPlus).mount(host);
    await flush();
    expect(api.formContext).toHaveBeenCalledWith('ls.asset.defectForm');
    expect(model.value).toMatchObject({ asset: 88, location: '一车间 A 区', reporter: 31, reportedAt: '2026-11-10T17:30:00', severity: 'HIGH' });
    const badge = (k: string) => host!.querySelector(`[data-testid="origin-${k}"]`);
    expect(badge('severity')?.textContent).toBe('上次填写');
    expect(badge('severity')?.className).toContain('is-remembered');
    expect(badge('location')?.textContent).toBe('自动带入');

    const input = host.querySelector('[data-testid="input-location"]') as HTMLInputElement;
    input.value = '二车间';
    input.dispatchEvent(new Event('input'));
    await flush();
    expect(model.value.location).toBe('二车间');
    expect(badge('location')).toBeNull();

    await inst.value.commitRemembered();
    expect(api.remember).toHaveBeenCalledWith('ls.asset.defectForm', { shift: 'EVENING', severity: 'HIGH' });
  });

  it('已有记录与只读时不预填', async () => {
    const api = { refSearch: vi.fn(async () => []), upload: vi.fn(), dict: vi.fn(async () => []), formContext: vi.fn(async () => ctx) };
    host = document.createElement('div');
    document.body.appendChild(host);
    const model = ref<Record<string, any>>({ id: 5, title: '旧' });
    app = createApp(
      defineComponent({
        setup: () => () =>
          h(SchemaForm as any, { schema: defectForm, api, modelValue: model.value, 'onUpdate:modelValue': (v: any) => (model.value = v) })
      })
    );
    app.use(ElementPlus).mount(host);
    await flush();
    expect(api.formContext).not.toHaveBeenCalled();
    expect(model.value.reporter).toBeUndefined();
  });
});
