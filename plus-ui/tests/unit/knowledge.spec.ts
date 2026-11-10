/**
 * 知识问答页（spec ai/agent-gateway「带引用的知识问答」）：指标问数显示数值、指标编码与记录引用；文档问答显示引用；
 * 修订稿保存后才能发布，无发布权限不显示发布按钮。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import KnowledgeCenter from '@/views/lsmom/agent/knowledge/KnowledgeCenter.vue';

let app: App | undefined;
let host: HTMLElement | undefined;

afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-drawer, .el-overlay').forEach((e) => e.remove());
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}

const doc = {
  id: 3,
  code: 'SOP-LOTO',
  title: '挂牌上锁',
  category: 'SOP',
  classification: 'INTERNAL',
  publishedVersion: 1,
  draftVersion: 2,
  versions: [
    { version: 2, status: 'DRAFT', content: '双人确认（修订中）', createdAt: '2026-11-10T09:00:00' },
    { version: 1, status: 'PUBLISHED', content: '挂牌上锁', createdAt: '2026-11-01T09:00:00' }
  ]
} as any;

function makeApi() {
  return {
    ask: vi.fn(async (q: string) =>
      q.includes('异常数')
        ? {
            type: 'METRIC',
            question: q,
            answer: '本周一车间甲班异常数：2 条',
            value: 2,
            unit: '条',
            metric: { code: 'ls.core.issueCount', version: 1, name: '异常数' },
            refs: [
              { type: 'ls.core.Issue', id: '41', label: 'IS-41 跑偏' },
              { type: 'ls.core.Issue', id: '42', label: 'IS-42 漏油' }
            ],
            drill: { metric: 'ls.core.issueCount', version: 1, total: 2 },
            citations: []
          }
        : {
            type: 'DOCUMENT',
            question: q,
            answer: '检修前必须挂牌上锁 [1]',
            citations: [{ n: 1, documentId: 3, documentCode: 'SOP-LOTO', title: '挂牌上锁', version: 1, chunkNo: 2, heading: '作业前', excerpt: '挂牌上锁', score: 0.4 }]
          }
    ),
    list: vi.fn(async () => [doc]),
    get: vi.fn(async () => doc),
    create: vi.fn(async () => doc),
    saveDraft: vi.fn(async () => doc),
    publish: vi.fn(async () => ({ ...doc, publishedVersion: 2, draftVersion: undefined }))
  };
}

async function mount(props: Record<string, unknown>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  const r = ref<any>();
  app = createApp({ setup: () => () => h(KnowledgeCenter, { ref: r, ...props }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return r;
}

const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
const input = (id: string) => (q(id)!.tagName === 'INPUT' || q(id)!.tagName === 'TEXTAREA' ? q(id) : q(id)!.querySelector('input,textarea')) as HTMLInputElement;

describe('知识问答', () => {
  it('问数：显示数值、指标编码与记录引用', async () => {
    const api = makeApi();
    await mount({ api });
    const i = input('in-question');
    i.value = '本周一车间甲班异常数';
    i.dispatchEvent(new Event('input'));
    await flush();
    q('btn-ask')!.click();
    await flush();
    expect(api.ask).toHaveBeenCalledWith('本周一车间甲班异常数');
    expect(q('answer-value')!.textContent).toContain('2');
    expect(q('answer-metric')!.textContent).toContain('ls.core.issueCount v1');
    expect(q('answer-ref-41')!.textContent).toContain('IS-41');
    expect(q('table-docs')).toBeNull();
  });

  it('文档问答：答案带引用编号与来源版本', async () => {
    const api = makeApi();
    await mount({ api });
    const i = input('in-question');
    i.value = '检修前怎么做';
    i.dispatchEvent(new Event('input'));
    await flush();
    q('btn-ask')!.click();
    await flush();
    expect(q('answer-text')!.textContent).toContain('[1]');
    expect(q('cite-1')!.textContent).toContain('SOP-LOTO v1');
  });

  it('维护：查看发布版本与修订稿，有发布权限才可发布', async () => {
    const api = makeApi();
    const r = await mount({ api, canManage: true, canPublish: false });
    expect(api.list).toHaveBeenCalled();
    await r.value.loadDocs();
    (document.querySelector('.el-tabs__item#tab-docs') as HTMLElement).click();
    await flush();
    (document.querySelector('[data-testid="table-docs"] .el-table__row') as HTMLElement).click();
    await flush(200);
    expect(q('published-content')!.textContent).toContain('挂牌上锁');
    expect(input('in-draft').value).toBe('双人确认（修订中）');
    expect(q('btn-publish-doc')).toBeNull();
    q('btn-save-draft')!.click();
    await flush();
    expect(api.saveDraft).toHaveBeenCalledWith(3, '双人确认（修订中）', '');
  });
});
