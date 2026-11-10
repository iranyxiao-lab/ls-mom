/**
 * 16.2：意见咨询——匿名议题不显示提交人、提交意见、答复与转整改、总结观点必须引用意见、总结引用可点开来源意见。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App, type Component } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import MyTopics from '@/views/lsmom/consultation/topics/MyTopics.vue';
import HandleBoard from '@/views/lsmom/consultation/handle/HandleBoard.vue';
import { authorLabel, summaryErrors } from '@/api/lsmom/consultationModel';

const topic = (over: Record<string, any> = {}) => ({
  id: 7,
  version: 1,
  status: 'OPEN',
  title: '安全隐患征集',
  description: '欢迎提出',
  scope_name: '一车间',
  deadline: '2026-11-20 18:00:00',
  anonymous: true,
  total: 2,
  pending: 2,
  converted: 0,
  ...over
});
const opinions = [
  {
    id: 31,
    version: 0,
    status: 'SUBMITTED',
    topic_id: 7,
    topic_title: '安全隐患征集',
    anonymous: true,
    content: '泵房照明不足',
    author_name: null,
    submitted_at: '2026-11-10 09:00:00'
  },
  {
    id: 32,
    version: 0,
    status: 'SUBMITTED',
    topic_id: 7,
    topic_title: '安全隐患征集',
    anonymous: true,
    content: '洗眼器缺失',
    author_name: null,
    submitted_at: '2026-11-10 09:05:00'
  }
];

function makeApi(over: Record<string, any> = {}) {
  const ok = { targetId: '1', version: 1, status: 'OK', warnings: [] as string[] };
  return {
    myTopics: vi.fn(async () => [topic({ my_count: 0 })]),
    myOpinions: vi.fn(async () => [] as any[]),
    topics: vi.fn(async () => [topic()]),
    opinions: vi.fn(async () => opinions),
    summary: vi.fn(async () => null as any),
    submit: vi.fn(async () => ({ ...ok, data: { opinionId: 33 } })),
    reply: vi.fn(async () => ok),
    toIssue: vi.fn(async () => ok),
    closeTopic: vi.fn(async () => ok),
    draftSummary: vi.fn(async () => ok),
    publishSummary: vi.fn(async () => ok),
    discardSummary: vi.fn(async () => ok),
    ...over
  };
}

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper, .el-message, .el-popper').forEach((e) => e.remove());
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}
const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
async function type(id: string, text: string) {
  const el0 = q(id)!;
  const el = (['INPUT', 'TEXTAREA'].includes(el0.tagName) ? el0 : el0.querySelector('input, textarea')) as HTMLInputElement;
  el.value = text;
  el.dispatchEvent(new Event('input'));
  await flush(30);
}
async function mount(comp: Component, api: any) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(comp, { ref: r, api }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return r;
}

describe('咨询模型', () => {
  it('匿名议题显示“匿名”；总结观点须引用意见', () => {
    expect(authorLabel(opinions[0] as any)).toBe('匿名');
    expect(authorLabel({ ...opinions[0], anonymous: false, author_name: '吴班长' } as any)).toBe('吴班长');
    expect(authorLabel({ ...opinions[0], submitter_name: '郑师傅' } as any)).toBe('郑师傅');
    expect(summaryErrors('', [])).toEqual(['请填写总结', '至少要有一个观点']);
    expect(summaryErrors('总结', [{ viewpoint: '照明', opinionIds: [] }])).toEqual(['第 1 个观点没有引用来源意见']);
    expect(summaryErrors('总结', [{ viewpoint: '照明', opinionIds: [31] }])).toEqual([]);
  });
});

describe('参与征求', () => {
  it('匿名议题提示不显示姓名；提交意见调用提交 Operation', async () => {
    const api = makeApi();
    await mount(MyTopics, api);
    q('btn-opine-7')!.click();
    await flush(150);
    expect(q('anonymous-hint')!.textContent).toContain('答复人看不到你的姓名');
    q('btn-opinion-save')!.click();
    await flush();
    expect(api.submit).not.toHaveBeenCalled();
    await type('opinion-content', '三号泵房照明不足');
    q('btn-opinion-save')!.click();
    await flush(150);
    expect(api.submit).toHaveBeenCalledWith(7, '三号泵房照明不足', undefined);
  });
});

describe('意见处理', () => {
  it('匿名议题不显示提交人；答复与转整改', async () => {
    const api = makeApi();
    const board = await mount(HandleBoard, api);
    await board.value.open(topic());
    await flush(200);
    expect(q('op-author-31')!.textContent).toBe('匿名');
    q('btn-reply-31')!.click();
    await flush(150);
    await type('reply-text', '已安排电工');
    q('btn-reply-save')!.click();
    await flush(150);
    expect(api.reply).toHaveBeenCalledWith(opinions[0], '已安排电工', undefined);
    q('btn-to-issue-32')!.click();
    await flush(150);
    (q('issue-sev-HIGH')!.querySelector('input') as HTMLInputElement).click();
    await flush();
    q('btn-issue-save')!.click();
    await flush(150);
    expect(api.toIssue).toHaveBeenCalledWith(opinions[1], { severity: 'HIGH' });
  });

  it('已截止：观点未引用意见时前端拦下；引用后保存草稿；草稿可确认发布', async () => {
    const api = makeApi();
    const board = await mount(HandleBoard, api);
    await board.value.open(topic({ status: 'CLOSED' }));
    await flush(200);
    await type('summary-content', '集中在照明与应急设施');
    await type('point-text-0', '照明不足');
    q('btn-summary-draft')!.click();
    await flush();
    expect(document.body.textContent).toContain('第 1 个观点没有引用来源意见');
    expect(api.draftSummary).not.toHaveBeenCalled();
    board.value.sDraft.points[0].opinionIds = [31, 32];
    await flush();
    q('btn-summary-draft')!.click();
    await flush(150);
    expect(api.draftSummary).toHaveBeenCalledWith(7, '集中在照明与应急设施', [{ viewpoint: '照明不足', opinionIds: [31, 32] }]);

    const draft = {
      id: 5,
      version: 0,
      status: 'DRAFT',
      origin: 'LLM',
      content: '总结',
      points: [{ seq: 1, viewpoint: '照明不足', opinions: [{ id: 31, content: '泵房照明不足' }] }]
    };
    api.summary.mockResolvedValue(draft);
    await board.value.open(topic({ status: 'CLOSED' }));
    await flush(200);
    expect(q('summary-status')!.textContent).toContain('草稿待确认');
    q('cite-1-31')!.click();
    await flush(150);
    expect(q('cite-content-31')!.textContent).toBe('泵房照明不足');
    q('btn-summary-publish')!.click();
    await flush(150);
    expect(api.publishSummary).toHaveBeenCalledWith(draft);
  });
});
