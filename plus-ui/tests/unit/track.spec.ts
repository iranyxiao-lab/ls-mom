/**
 * modernize-ui-ux 2.5：匿名埋点——只发任务名、步骤名、事件类型和耗时；表单内容不出现在上报里；关闭开关后不再上报。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import NcBoard from '@/views/lsmom/quality/nc/NcBoard.vue';
import UsageReport from '@/views/lsmom/ui-usage/UsageReport.vue';
import { flushTrack, resetTrack, track } from '@/utils/track';
import { setToken, removeToken } from '@/utils/auth';

const SECRET = '泵站P101漏油-张三13800000000';

let usageEnabled = true;
let sent: any[][] = [];
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  resetTrack();
  usageEnabled = true;
  sent = [];
  setToken('unit-test-token');
  fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/api/ui/usage/config')) return new Response(JSON.stringify({ code: 200, data: { enabled: usageEnabled } }));
    if (url.endsWith('/api/ui/usage/events')) {
      sent.push(JSON.parse(String(init?.body)));
      return new Response(JSON.stringify({ code: 200, data: { accepted: 1 } }));
    }
    throw new Error('unexpected ' + url);
  });
  vi.stubGlobal('fetch', fetchMock);
});

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-message, .el-popper').forEach((e) => e.remove());
  vi.unstubAllGlobals();
  removeToken();
  resetTrack();
});

const settle = async (ms = 60) => {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
};
const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement;

describe('track()', () => {
  it('只发送白名单字段，非标识符名称被丢弃', async () => {
    track('start', { task: 'nc.register', extra: SECRET } as any);
    track('step', { task: 'nc.register', step: SECRET, durationMs: 12.7 });
    track('start', { task: SECRET });
    track('submit' as any, { task: 'nc.register' });
    await flushTrack();
    expect(sent).toEqual([
      [
        { task: 'nc.register', kind: 'start' },
        { task: 'nc.register', kind: 'step', durationMs: 13 }
      ]
    ]);
    expect(JSON.stringify(sent)).not.toContain('泵站');
  });

  it('管理员关闭后不再上报', async () => {
    usageEnabled = false;
    track('start', { task: 'nc.register' });
    await flushTrack();
    track('abandon', { task: 'nc.register', step: 'asset' });
    await flushTrack();
    expect(sent).toEqual([]);
    expect(fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/events'))).toHaveLength(0);
  });
});

describe('登记不符合项的放弃点', () => {
  async function openDialog() {
    const api = {
      objects: vi.fn(async () => [{ id: 3, label: 'P-101 循环泵' }]),
      orgs: vi.fn(async () => [{ id: 7, name: '一车间', unit_type: 'WORKSHOP' }]),
      query: vi.fn(async () => ({ rows: [], total: 0, page: 1, size: 100 })),
      create: vi.fn(async () => ({ id: 99, version: 0 })),
      op: vi.fn(async () => ({ targetId: '99', version: 1, status: 'OK', warnings: [], data: {} }))
    };
    host = document.createElement('div');
    document.body.appendChild(host);
    app = createApp({ setup: () => () => h(NcBoard, { api: api as any }) });
    app.use(ElementPlus);
    app.mount(host);
    await settle();
    q('btn-new-nc').click();
    await settle(120);
    return api;
  }
  async function typeInto(id: string, text: string) {
    const t = q(id);
    const el = (['INPUT', 'TEXTAREA'].includes(t.tagName) ? t : t.querySelector('input, textarea')) as HTMLInputElement;
    el.focus();
    el.value = text;
    el.dispatchEvent(new Event('input'));
    await settle(20);
  }

  it('填写后在“相关设备”一步取消：记为该步骤放弃，上报中不含填写内容', async () => {
    await openDialog();
    await typeInto('nc-title', SECRET);
    await typeInto('nc-description', SECRET + '描述');
    const asset = q('nc-asset');
    ((asset.tagName === 'INPUT' ? asset : asset.querySelector('input')) as HTMLInputElement).focus();
    await settle(20);
    [...document.querySelectorAll('.el-dialog button')]
      .find((b) => b.textContent?.includes('取消'))!
      .dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await settle(120);
    await flushTrack();

    const events = sent.flat();
    expect(events.map((e) => e.kind)).toEqual(['start', 'step', 'step', 'step', 'abandon']);
    expect(events.filter((e) => e.kind === 'step').map((e) => e.step)).toEqual(['title', 'description', 'asset']);
    expect(events.at(-1)).toMatchObject({ task: 'nc.register', kind: 'abandon', step: 'asset' });
    const payload = JSON.stringify(sent);
    expect(payload).not.toContain('泵站');
    expect(payload).not.toContain('13800000000');
    for (const e of events) expect(Object.keys(e).every((k) => ['task', 'step', 'kind', 'durationMs'].includes(k))).toBe(true);
  });

  it('提交成功记为完成，不再记放弃', async () => {
    const api = await openDialog();
    await typeInto('nc-title', '漏油');
    await typeInto('nc-description', '法兰渗漏');
    q('btn-save-nc').click();
    await settle(150);
    await flushTrack();
    expect(api.create).toHaveBeenCalled();
    const kinds = sent.flat().map((e) => e.kind);
    expect(kinds.at(-1)).toBe('complete');
    expect(kinds).not.toContain('abandon');
  });
});

describe('放弃点报表页', () => {
  it('高放弃率步骤有标记，管理员可关闭埋点', async () => {
    const api = {
      config: vi.fn(async () => ({ enabled: true })),
      setEnabled: vi.fn(async (v: boolean) => ({ enabled: v })),
      report: vi.fn(async () => [
        {
          task: 'nc.register',
          started: 10,
          completed: 5,
          abandoned: 5,
          errors: 1,
          medianMs: 42000,
          steps: [
            { step: 'asset', abandoned: 4, rate: 0.4, high: true },
            { step: 'title', abandoned: 1, rate: 0.1, high: false }
          ]
        }
      ])
    };
    host = document.createElement('div');
    document.body.appendChild(host);
    app = createApp({ setup: () => () => h(UsageReport, { api: api as any, canManage: true }) });
    app.use(ElementPlus);
    app.mount(host);
    await settle(150);
    const asset = q('usage-step-nc.register-asset');
    expect(asset.textContent).toContain('选择设备');
    expect(asset.textContent).toContain('高放弃率');
    expect(q('usage-step-nc.register-title').textContent).not.toContain('高放弃率');
    expect(q('usage-table').textContent).toContain('登记不符合项');
    expect(q('usage-table').textContent).toContain('42.0 秒');
    q('usage-switch').click();
    await settle(80);
    expect(api.setEnabled).toHaveBeenCalledWith(false);
  });
});
