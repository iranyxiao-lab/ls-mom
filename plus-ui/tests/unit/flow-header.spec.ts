import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, nextTick, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import FlowHeader from '@/components/LsFlow/FlowHeader.vue';
import type { FlowProgress } from '@/api/lsmom/flowProgress';

/** modernize-ui-ux 6.1/6.2：单据头部"状态与下一步"与流程进度 */
const zhang = { party: 7, name: '张三', role: '车间主任' };
const li = { party: 3, name: '李四', role: '班组长' };
const steps = (audit: 'current' | 'returned', initiator: 'done' | 'current' = 'done') => [
  { key: 'initiator', name: '发起', type: 'INITIATOR', state: initiator, records: [{ by: li, at: '2026-11-10T08:00:00', outcome: 'SUBMITTED' }] },
  {
    key: 'deptAudit',
    name: '部门审核',
    type: 'AUDIT',
    state: audit,
    records: audit === 'returned' ? [{ by: zhang, at: '2026-11-10T09:30:00', outcome: 'RETURNED', comment: '借用数量不清楚' }] : []
  },
  { key: 'confirm', name: '确认归档', type: 'CONFIRM', state: 'pending', records: [] }
];
const base = (over: Partial<FlowProgress>): FlowProgress => ({
  flow: { instanceId: 1, flowName: '借还审批', status: 'RUNNING', initiator: li, startedAt: '2026-11-10T08:00:00', endedAt: null },
  steps: steps('current') as any,
  current: [
    {
      taskId: 't1',
      nodeKey: 'deptAudit',
      nodeName: '部门审核',
      handlers: [zhang],
      dueAt: '2026-11-11T04:00:00',
      remainingMinutes: 20 * 60 + 5,
      urgency: 'NORMAL',
      mine: false
    }
  ],
  myTask: null,
  canUrge: true,
  returned: null,
  ...over
});
function api(p: FlowProgress, ctx: any = { fieldPolicy: null, form: null, record: {} }) {
  return {
    progress: vi.fn(async () => p),
    context: vi.fn(async () => ctx),
    complete: vi.fn(async () => ({})),
    returnTask: vi.fn(async () => ({})),
    urge: vi.fn(async () => ({})),
    transfer: vi.fn(),
    parties: vi.fn(async () => [])
  };
}

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-message, .el-popper, .el-overlay').forEach((e) => e.remove());
});
const flush = async (ms = 60) => {
  for (let i = 0; i < 4; i++) await nextTick();
  await new Promise((r) => setTimeout(r, ms));
};
const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
async function mount(a: ReturnType<typeof api>) {
  host = document.createElement('div');
  document.body.appendChild(host);
  app = createApp(FlowHeader, { concept: 'acme.Loan', id: 5, api: a, statusLabel: '审核中' });
  app.use(ElementPlus).mount(host);
  await flush(100);
}

describe('FlowHeader', () => {
  it('发起人：显示等待谁审核与剩余时间，可催办，没有办理按钮', async () => {
    const a = api(base({}));
    await mount(a);
    expect(q('flow-status')!.textContent).toBe('审核中');
    expect(q('flow-waiting')!.textContent).toBe('等待 张三（车间主任）审核');
    expect(q('flow-header')!.textContent).toContain('当前：部门审核');
    expect(q('flow-header')!.textContent).toContain('剩余 20 小时');
    expect(q('flow-main-action')).toBeNull();
    q('flow-urge')!.click();
    await flush();
    expect(a.urge).toHaveBeenCalledWith('t1');
    expect(document.querySelector('.el-message')!.textContent).toContain('已催办 张三（车间主任）');
  });

  it('审核人：唯一主按钮"审核通过"、意见框直接可见；节点要求的字段在头部填写后一并提交', async () => {
    const p = base({ current: [{ ...base({}).current[0], mine: true }], myTask: 't1', canUrge: false });
    const a = api(p, {
      fieldPolicy: { editable: ['auditNote'], required: ['auditNote'] },
      form: { fields: [{ key: 'auditNote', label: '审核意见', widget: 'textarea' }] },
      record: { auditNote: '' }
    });
    await mount(a);
    expect(q('flow-my-turn')!.textContent).toContain('轮到你审核');
    expect(q('flow-urge')).toBeNull();
    const main = q('flow-main-action') as HTMLButtonElement;
    expect(main.textContent!.trim()).toBe('审核通过');
    expect(document.querySelectorAll('[data-testid="flow-act"] .el-button--primary:not(.is-link)').length).toBe(1);
    expect(q('flow-comment')).not.toBeNull();
    main.click();
    await flush();
    expect(a.complete).not.toHaveBeenCalled();
    expect(document.querySelector('.el-message')!.textContent).toContain('请填写审核意见');
    const f = q('flow-field-auditNote')!;
    const ta = (f.tagName === 'TEXTAREA' ? f : f.querySelector('textarea')) as HTMLTextAreaElement;
    ta.value = '同意借用';
    ta.dispatchEvent(new Event('input'));
    const c = q('flow-comment')!;
    const cta = (c.tagName === 'TEXTAREA' ? c : c.querySelector('textarea')) as HTMLTextAreaElement;
    cta.value = '按时归还';
    cta.dispatchEvent(new Event('input'));
    await flush();
    main.click();
    await flush();
    expect(a.complete).toHaveBeenCalledWith('t1', { action: 'APPROVE', comment: '按时归还', input: { auditNote: '同意借用' } });
    expect(a.progress).toHaveBeenCalledTimes(2);
  });

  it('退回：醒目显示退回节点与意见；步骤条标出退回节点，点击查看办理人、时间和意见', async () => {
    const p = base({
      steps: steps('returned', 'current') as any,
      current: [
        {
          taskId: 't2',
          nodeKey: 'initiator',
          nodeName: '发起人修改',
          handlers: [li],
          dueAt: null,
          remainingMinutes: null,
          urgency: 'NORMAL',
          mine: true
        }
      ],
      myTask: 't2',
      canUrge: false,
      returned: { nodeKey: 'deptAudit', nodeName: '部门审核', by: zhang, at: '2026-11-10T09:30:00', comment: '借用数量不清楚' }
    });
    const a = api(p);
    await mount(a);
    const ret = q('flow-returned')!;
    expect(ret.textContent).toContain('被 张三（车间主任） 退回（部门审核）');
    expect(ret.textContent).toContain('退回意见：借用数量不清楚');
    expect(q('flow-main-action')!.textContent!.trim()).toBe('再提交');
    expect(q('flow-return')).toBeNull();
    expect(q('flow-step-deptAudit')!.querySelector('.is-error')).not.toBeNull();
    q('flow-step-btn-deptAudit')!.click();
    await flush(200);
    const detail = q('flow-step-detail-deptAudit')!;
    expect(detail.textContent).toContain('部门审核 · 已退回');
    expect(detail.textContent).toContain('张三（车间主任） 退回');
    expect(detail.textContent).toContain('2026-11-10 09:30');
    expect(detail.textContent).toContain('意见：借用数量不清楚');
    q('flow-main-action')!.click();
    await flush();
    expect(a.complete).toHaveBeenCalledWith('t2', { action: 'RESUBMIT', comment: '', input: null });
  });

  it('没有流程时不显示', async () => {
    const a = api({ flow: null } as any);
    await mount(a);
    expect(q('flow-header')).toBeNull();
  });
});
