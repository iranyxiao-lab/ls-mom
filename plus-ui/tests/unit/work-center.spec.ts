/**
 * 11.3：协同工作中心——待办超时/即将到期标识与行样式、消息与待办分离（已读不移除待办）、在待办中办理提交可编辑字段、转办。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import WorkCenter from '@/views/lsmom/workbench/WorkCenter.vue';

const task = (id: string, extra: Record<string, any> = {}) => ({
  id: Number(id.replace(/\D/g, '')) || 1,
  taskId: id,
  kind: 'TASK',
  flowInstanceId: 1,
  procInstId: 'p1',
  bizType: 'acme.tool.LoanOrder',
  bizId: '7',
  nodeKey: 'deptAudit',
  nodeName: '部门审核',
  candidates: [],
  status: 'TODO',
  createdAt: '2026-11-10T08:00:00',
  ...extra
});

function makeApi() {
  const state = {
    todo: ['t1', 't2', 't3'],
    unread: [{ id: 51, kind: 'URGE', taskId: 't2', message: '催办：部门审核', createdAt: '2026-11-10T09:00:00' }] as any[]
  };
  const api = {
    counts: vi.fn(async () => ({ todo: state.todo.length, overdue: 1, ccUnread: 0, messagesUnread: state.unread.filter((m) => !m.readAt).length })),
    todo: vi.fn(async () =>
      state.todo.map((id, i) => ({
        task: task(id, { dueAt: ['2026-11-09T08:00:00', '2026-11-10T20:00:00', '2026-11-20T08:00:00'][i] }),
        urgency: ['OVERDUE', 'DUE_SOON', 'NORMAL'][i],
        initiatorName: '申请人',
        bizTitle: 'L-' + id
      }))
    ),
    done: vi.fn(async () => []),
    started: vi.fn(async () => []),
    cc: vi.fn(async () => []),
    delegations: vi.fn(async () => []),
    messages: vi.fn(async () => state.unread),
    readMessages: vi.fn(async (ids?: number[]) => {
      state.unread.forEach((m) => (!ids || ids.includes(m.id) ? (m.readAt = '2026-11-10T10:00:00') : null));
      return 1;
    }),
    parties: vi.fn(async () => [{ id: 12, name: '审核二', orgName: '设备部' }]),
    context: vi.fn(async (taskId: string) => ({
      task: task(taskId),
      urgency: 'DUE_SOON',
      instance: {
        id: 1,
        flowCode: 'acme.tool.loanApproval',
        flowName: '借还单审批',
        flowVersion: 2,
        formCode: 'acme.tool.loanForm',
        formVersion: 1,
        initiatorName: '申请人'
      },
      node: { key: 'deptAudit', name: '部门审核', type: 'AUDIT', operation: 'acme.tool.LoanOrder.audit' },
      actions: { approve: true, reject: true, return: true, transfer: true, resubmit: false, urge: false },
      returnTargets: [{ key: 'initiator', name: '发起人' }],
      form: {
        viewType: 'FORM',
        formCode: 'acme.tool.loanForm',
        version: 1,
        conceptCode: 'acme.tool.LoanOrder',
        layout: [
          { type: 'field', key: 'loanNo' },
          { type: 'field', key: 'auditNote' }
        ],
        fields: [
          { key: 'loanNo', label: '借用单号', widget: 'input' },
          { key: 'auditNote', label: '审核意见', widget: 'textarea' }
        ]
      },
      fieldPolicy: { readonly: ['*'], editable: ['auditNote'], required: ['auditNote'] },
      record: { loanNo: 'L-1', auditNote: '' },
      snapshot: {
        flowCode: 'acme.tool.loanApproval',
        flowVersion: 2,
        formCode: 'acme.tool.loanForm',
        formVersion: 1,
        recordStatus: 'IN_REVIEW',
        recordVersion: 3
      },
      timeline: [{ at: '2026-11-10T08:00:00', type: 'ls.flow.started', action: '流程启动', actorName: '申请人', payload: { label: '发起' } }]
    }))
  };
  const actions = {
    complete: vi.fn(async (id: string) => {
      state.todo = state.todo.filter((x) => x !== id);
      return { taskId: id, outcome: 'APPROVED' };
    }),
    returnTask: vi.fn(),
    transfer: vi.fn(async (id: string) => {
      state.todo = state.todo.filter((x) => x !== id);
    }),
    urge: vi.fn(),
    parties: api.parties
  };
  return { api, actions, state };
}

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper').forEach((e) => e.remove());
});
async function flush(ms = 80) {
  for (let i = 0; i < 5; i++) await nextTick();
  await new Promise((r) => setTimeout(r, ms));
}
const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
const tabClick = async (label: RegExp) => {
  ([...host!.querySelectorAll('.el-tabs__item')].find((e) => label.test((e.textContent ?? '').trim())) as HTMLElement).click();
  await flush(150);
};

async function mount() {
  const m = makeApi();
  host = document.createElement('div');
  host.style.width = '1400px';
  document.body.appendChild(host);
  const wc = ref<any>();
  app = createApp({ setup: () => () => h(WorkCenter, { ref: wc, api: m.api as any, actions: m.actions as any }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush(200);
  return { ...m, wc };
}

describe('协同工作中心', () => {
  it('待办：超时与即将到期在前并有标识', async () => {
    await mount();
    const rows = [...q('table-todo')!.querySelectorAll('.el-table__body tr')];
    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain('已超时');
    expect(rows[0].className).toContain('row-overdue');
    expect(rows[1].textContent).toContain('即将到期');
    expect(rows[1].className).toContain('row-soon');
    expect(rows[2].textContent).not.toMatch(/超时|到期/);
    expect(rows[0].textContent).toContain('L-t1');
  });

  it('已读不等于完成：消息标为已读后待办仍在，办理后才消失', async () => {
    const { api, actions, state } = await mount();
    await tabClick(/^消息/);
    expect(q('table-messages')!.textContent).toContain('催办：部门审核');
    q('read-51')!.click();
    await flush(150);
    expect(api.readMessages).toHaveBeenCalledWith([51]);
    expect(q('table-messages')!.textContent).toContain('已读');
    await tabClick(/^我的待办/);
    expect(q('table-todo')!.textContent).toContain('L-t2');

    // 从待办打开办理：表单按节点策略只允许编辑审核意见，提交时只带可编辑字段
    (q('table-todo')!.querySelectorAll('.el-table__body tr')[1] as HTMLElement).click();
    await flush(300);
    expect(api.context).toHaveBeenCalledWith('t2');
    expect(q('snapshot')!.textContent).toContain('acme.tool.loanApproval v2');
    expect(q('snapshot')!.textContent).toContain('acme.tool.loanForm v1');
    const form = q('task-form')!;
    // 借用单号只读（不出现可编辑输入框），审核意见可编辑
    expect(form.textContent).toContain('L-1');
    const editable = [...form.querySelectorAll('input, textarea')].filter(
      (e) => !(e as HTMLInputElement).disabled && !(e as HTMLInputElement).readOnly
    );
    expect(editable).toHaveLength(1);
    const note = editable[0] as HTMLTextAreaElement;
    expect(note.tagName).toBe('TEXTAREA');
    note.value = '同意借用';
    note.dispatchEvent(new Event('input'));
    // el-input 的 data-testid 落在 textarea 本身
    const c = q('task-comment') as HTMLTextAreaElement;
    c.value = '同意';
    c.dispatchEvent(new Event('input'));
    await flush();
    q('btn-approve')!.click();
    await flush(300);
    expect(actions.complete).toHaveBeenCalledWith('t2', { action: 'APPROVE', comment: '同意', input: { auditNote: '同意借用' } });
    expect(state.todo).not.toContain('t2');
    expect(q('table-todo')!.textContent).not.toContain('L-t2');
  });
});
