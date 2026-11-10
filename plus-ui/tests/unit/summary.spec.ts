/**
 * 摘要确认页（spec ai/agent-gateway AI-09）：草稿显示要点引用与 Agent 来源，真人确认发布；作废须填原因。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import SummaryReview from '@/views/lsmom/agent/summaries/SummaryReview.vue';

let app: App | undefined;
let host: HTMLElement | undefined;

afterEach(() => {
  app?.unmount();
  host?.remove();
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}

const draft = {
  id: 7,
  kind: 'ISSUE',
  subjectConcept: 'ls.core.Issue',
  subjectId: '41',
  title: '异常 ISS-1 传送带跑偏',
  content: '传送带跑偏已登记，整改中',
  points: [
    { text: '已登记缺陷', refs: [{ type: 'ls.core.Issue', id: '41', label: 'ISS-1' }] },
    { text: '整改工作进行中', refs: [{ type: 'ls.core.Work', id: '88', label: 'W-88' }] }
  ],
  citations: [],
  status: 'DRAFT',
  source: 'AGENT',
  model: 'qwen-test',
  createdAt: '2026-11-10T09:00:00',
  version: 0
} as const;

function makeApi() {
  return {
    list: vi.fn(async (q: { status?: string }) => (q.status === 'DRAFT' ? [draft] : [])),
    publish: vi.fn(async () => ({})),
    discard: vi.fn(async () => ({}))
  };
}

async function mount(api: ReturnType<typeof makeApi>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(SummaryReview, { ref: r, api }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return r;
}

const q = (id: string) => host!.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

describe('摘要确认', () => {
  it('草稿显示 Agent 来源与每个要点的引用；确认发布带版本', async () => {
    const api = makeApi();
    await mount(api);
    expect(api.list).toHaveBeenCalledWith({ status: 'DRAFT' });
    expect(q('source-7')!.textContent).toContain('Agent 草拟');
    expect(q('source-7')!.textContent).toContain('qwen-test');
    expect(q('ref-7-ls.core.Issue-41')!.textContent).toContain('异常 ISS-1');
    expect(q('ref-7-ls.core.Work-88')!.textContent).toContain('工作 W-88');
    q('btn-publish-7')!.click();
    await flush();
    expect(api.publish).toHaveBeenCalledWith(expect.objectContaining({ id: 7, version: 0 }));
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('作废须填写原因', async () => {
    const api = makeApi();
    await mount(api);
    q('btn-discard-7')!.click();
    await flush();
    expect(api.discard).not.toHaveBeenCalled();
    const input = q('reason-7')!.querySelector('input') ?? (q('reason-7') as HTMLInputElement);
    (input as HTMLInputElement).value = '引用不完整';
    input.dispatchEvent(new Event('input'));
    await flush();
    q('btn-discard-7')!.click();
    await flush();
    expect(api.discard).toHaveBeenCalledWith(expect.objectContaining({ id: 7 }), '引用不完整');
  });
});
