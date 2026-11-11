/**
 * 10.4 / 10.5：审批流树形配置器——插入节点、或签/会签、条件与并行分支、服务节点只选已发布 Operation、节点字段策略、
 * 校验问题定位、发布只提交 JSON 配置（无 XML 导入）；LogicFlow 只读预览与 v1/v2 差异高亮新增会签节点。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import Workspace from '@/views/lsmom/flow-designer/Workspace.vue';
import { parseBpmn, ownerKey } from '@/views/lsmom/flow-designer/bpmnGraph';
import { diffDefs, clean, type FlowDef } from '@/views/lsmom/flow-designer/model';

const ops = [
  { operation: 'acme.tool.LoanOrder.submit', name: '提交' },
  { operation: 'acme.tool.LoanOrder.audit', name: '审核' },
  { operation: 'acme.tool.LoanOrder.archive', name: '归档' },
  { operation: 'acme.tool.LoanOrder.reject', name: '驳回' }
];

const v1: FlowDef = {
  code: 'acme.tool.loanApproval',
  name: '借还单审批',
  concept: 'acme.tool.LoanOrder',
  startOn: 'acme.tool.LoanOrder.submit',
  form: { code: 'acme.tool.loanForm', version: 1 },
  callbacks: { approved: 'acme.tool.LoanOrder.archive', rejected: 'acme.tool.LoanOrder.reject' },
  orgUnitId: 1,
  nodes: [
    {
      key: 'deptAudit',
      type: 'AUDIT',
      name: '部门审核',
      assignee: { strategy: 'DEPT_LEADER', of: 'INITIATOR' },
      mode: 'SINGLE',
      operation: 'acme.tool.LoanOrder.audit'
    }
  ]
};

/** 与后端模板同形的最小 BPMN（部门审核 → 财务会签 → 通过）。 */
function bpmn(def: FlowDef) {
  const tasks = def.nodes
    .map(
      (n) => `<serviceTask id="resolve_${n.key}" name="解析办理人：${n.name}"/>
      <userTask id="${n.key}" name="${n.name}">${
        n.mode === 'AND'
          ? '<multiInstanceLoopCharacteristics isSequential="false"><completionCondition>${outcome == \'REJECTED\'}</completionCondition></multiInstanceLoopCharacteristics>'
          : n.mode === 'OR'
            ? '<multiInstanceLoopCharacteristics isSequential="false"><completionCondition>${nrOfCompletedInstances >= 1}</completionCondition></multiInstanceLoopCharacteristics>'
            : ''
      }</userTask>
      <exclusiveGateway id="gw_${n.key}" name="审核结果"/>`
    )
    .join('');
  const ids = ['start', 'initiator', ...def.nodes.flatMap((n) => [`resolve_${n.key}`, n.key, `gw_${n.key}`]), 'end_approved'];
  const flows = ids
    .slice(1)
    .map((t, i) => `<sequenceFlow id="flow_${i}" sourceRef="${ids[i]}" targetRef="${t}"/>`)
    .join('');
  const rej = def.nodes.map((n, i) => `<sequenceFlow id="flow_r${i}" sourceRef="gw_${n.key}" targetRef="end_rejected"/>`).join('');
  return `<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"><process id="p">
    <startEvent id="start" name="开始"/><userTask id="initiator" name="发起人"/>${tasks}
    <endEvent id="end_approved" name="通过"/><endEvent id="end_rejected" name="驳回"><terminateEventDefinition/></endEvent>${flows}${rej}</process></definitions>`;
}

function makeApi() {
  const store = new Map<number, { version: number; status: string; definition: FlowDef }>([[1, { version: 1, status: 'PUBLISHED', definition: v1 }]]);
  return {
    store,
    validate: vi.fn(async (d: FlowDef) => {
      const out: any[] = [];
      if (!d.callbacks?.rejected) out.push({ path: '/callbacks/rejected', code: 'CALLBACK_REQUIRED', message: '驳回回调必填' });
      d.nodes.forEach((n, i) => {
        if (n.type === 'SERVICE' && !n.operation)
          out.push({ path: `/nodes/${i}/operation`, code: 'NODE_OPERATION_REQUIRED', message: `${n.name} 须选择 Operation` });
      });
      return out;
    }),
    preview: vi.fn(async (d: FlowDef) => ({ issues: [], bpmnXml: bpmn(d) })),
    saveDraft: vi.fn(async () => ({ version: 2, status: 'DRAFT' })),
    publish: vi.fn(async (d: FlowDef) => {
      store.set(2, { version: 2, status: 'PUBLISHED', definition: d });
      return { version: 2, status: 'PUBLISHED' };
    }),
    history: vi.fn(async () => [...store.values()].map((x) => ({ version: x.version, status: x.status })).reverse()),
    get: vi.fn(async (_c: string, v: number) => store.get(v)!),
    formFields: vi.fn(async () => [
      { key: 'loanNo', label: '借用单号' },
      { key: 'amount', label: '金额' },
      { key: 'auditNote', label: '审核意见' }
    ]),
    parties: vi.fn(async () => [
      { id: 11, name: '财务甲', orgName: '财务部' },
      { id: 12, name: '财务乙', orgName: '财务部' }
    ])
  };
}

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  app = null;
});

async function flush(ms = 60) {
  for (let i = 0; i < 6; i++) await nextTick();
  await new Promise((r) => setTimeout(r, ms));
}
const q = (id: string) => host!.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
const click = async (id: string) => {
  q(id)!.click();
  await flush();
};

async function mount(api: any, definition: FlowDef = v1) {
  host = document.createElement('div');
  host.style.width = '1500px';
  document.body.appendChild(host);
  const ws = ref<any>();
  app = createApp({
    setup: () => () =>
      h(Workspace, {
        ref: ws,
        definition,
        status: 'PUBLISHED',
        version: 1,
        codeLocked: true,
        operations: ops,
        roles: [{ roleKey: 'finance', roleName: '财务' }],
        orgs: [{ id: 1, name: '一车间' }],
        conceptFields: [{ code: 'ownerParty', label: '负责人' }],
        forms: [{ code: 'acme.tool.loanForm', version: 1, title: '借还单' }],
        api
      })
  });
  app.use(ElementPlus);
  app.mount(host);
  await flush(200);
  return ws;
}

/** 点「＋」并在菜单中选节点类型。 */
async function insert(addId: string, type: string) {
  q(addId)!.click();
  await flush(150);
  const item = [...document.querySelectorAll(`[data-testid="pick-${type}"]`)].find((e) => (e as HTMLElement).offsetParent !== null) as HTMLElement;
  item.click();
  await flush();
}

describe('审批流配置器', () => {
  it('树形插入节点、会签、条件与并行分支、字段策略，发布只提交 JSON 配置', async () => {
    const api = makeApi();
    const ws = await mount(api);
    expect(q('node-deptAudit')).not.toBeNull();
    // 没有任何 XML 导入入口
    expect(host!.textContent).not.toMatch(/导入\s*XML|导入 BPMN/);
    expect(host!.querySelector('input[type="file"]')).toBeNull();

    // 部门审核后插入审核节点 → 会签、指定人员
    await insert('add-root-1', 'AUDIT');
    const d = ws.value.def as FlowDef;
    const audit = d.nodes[1];
    expect(audit.type).toBe('AUDIT');
    expect(ws.value.selected).toBe(audit.key);
    audit.name = '财务会签';
    audit.assignee = { strategy: 'USER', value: [11, 12] };
    await flush();
    (q('mode-AND')!.querySelector('input') ?? q('mode-AND')!).click();
    await flush();
    expect(audit.mode).toBe('AND');
    expect(q(`node-${audit.key}`)!.textContent).toContain('会签');
    expect(q(`node-${audit.key}`)!.textContent).toContain('财务甲');

    // 字段策略：其余字段只读
    (q('policy-rest-readonly')!.querySelector('input') as HTMLInputElement).click();
    await flush();
    expect(audit.fieldPolicy!.readonly).toEqual(['*']);

    // 条件分支 + 分支内抄送；并行分支两个确认
    await insert('add-root-2', 'CONDITION');
    const cond = d.nodes[2];
    expect(cond.branches).toHaveLength(2);
    cond.branches![0].when = '$model.amount > 10000';
    await flush();
    await insert(`add-root-${cond.key}-0-0`, 'CC');
    expect(cond.branches![0].nodes[0].type).toBe('CC');
    await click(`add-branch-${cond.key}`);
    expect(cond.branches).toHaveLength(3);
    expect(cond.branches![2].when ?? '').toBe(''); // 默认分支保持在最后
    await insert('add-root-3', 'PARALLEL');
    const par = d.nodes[3];
    await insert(`add-root-${par.key}-0-0`, 'CONFIRM');
    await insert(`add-root-${par.key}-1-0`, 'CONFIRM');
    expect(q(`branches-${par.key}`)!.querySelectorAll('.node-card')).toHaveLength(2);

    // 服务节点：Operation 只能从已发布列表中选择；未选时校验报错并可定位
    await insert('add-root-4', 'SERVICE');
    await expect.poll(() => d.nodes[4]?.type, { timeout: 5000 }).toBe('SERVICE');
    const svc = d.nodes[4];
    // 慢机器上按钮可能还在加载状态：轮询中重复点"校验"，直到结果出现
    await expect
      .poll(
        async () => {
          if (!q('issues')?.textContent?.includes('须选择 Operation')) await click('btn-validate');
          return q('issues')?.textContent ?? '';
        },
        { timeout: 8000 }
      )
      .toContain('须选择 Operation');
    ws.value.selected = null;
    await flush();
    (q('issues')!.querySelector('.issue') as HTMLElement).click();
    await flush();
    expect(ws.value.selected).toBe(svc.key);
    expect(q(`node-${svc.key}`)!.classList.contains('invalid')).toBe(true);
    const opSelect = q('node-operation')!;
    opSelect.querySelector<HTMLElement>('.el-select__wrapper')!.click();
    await flush(150);
    const options = [...document.querySelectorAll('.el-select-dropdown__item')]
      .filter((e) => (e as HTMLElement).offsetParent !== null)
      .map((e) => e.textContent);
    expect(options.every((t) => ops.some((o) => t!.includes(o.operation.substring(o.operation.lastIndexOf('.') + 1))))).toBe(true);
    svc.operation = 'acme.tool.LoanOrder.archive';

    // 删除节点
    await click(`del-${svc.key}`);
    expect(d.nodes).toHaveLength(4);

    // 发布：先校验，再提交清理后的 JSON
    await click('btn-publish');
    await flush(100);
    expect(api.publish).toHaveBeenCalledTimes(1);
    const sent = api.publish.mock.calls[0][0] as FlowDef;
    expect(typeof sent).toBe('object');
    expect(JSON.stringify(sent)).not.toContain('<?xml');
    expect(sent.nodes.map((n) => n.type)).toEqual(['AUDIT', 'AUDIT', 'CONDITION', 'PARALLEL']);
    expect(sent.nodes[1]).toMatchObject({ mode: 'AND', assignee: { strategy: 'USER', value: [11, 12] }, fieldPolicy: { readonly: ['*'] } });
    expect(sent.nodes[0].timeout).toBeUndefined(); // 空超时被去掉
    expect(sent.nodes[2].branches![0]).toMatchObject({ when: '$model.amount > 10000', nodes: [{ type: 'CC' }] });
    expect(q('flow-status')!.textContent).toContain('已发布 v2');
  });

  it('缺少驳回回调时发布被阻止', async () => {
    const api = makeApi();
    await mount(api, { ...v1, callbacks: { approved: v1.callbacks.approved } });
    await click('btn-publish');
    expect(api.publish).not.toHaveBeenCalled();
    await expect.poll(() => q('issues')?.textContent ?? '', { timeout: 5000 }).toContain('驳回回调必填');
  });

  it('LogicFlow 只读预览，v1/v2 差异高亮新增会签节点', async () => {
    const api = makeApi();
    const v2: FlowDef = JSON.parse(JSON.stringify(v1));
    v2.nodes.push({ key: 'finance', type: 'AUDIT', name: '财务会签', mode: 'AND', assignee: { strategy: 'ROLE', value: 'finance' } });
    v2.nodes[0].name = '部门负责人审核';
    api.store.set(2, { version: 2, status: 'PUBLISHED', definition: v2 });
    await mount(api);
    [...host!.querySelectorAll('.el-tabs__item')]
      .find((e) => e.textContent?.trim() === '预览')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flush(400);
    const canvas = q('lf-canvas')!;
    expect(Number(canvas.dataset.nodes)).toBeGreaterThan(5);
    expect(canvas.querySelector('svg')).not.toBeNull();

    [...host!.querySelectorAll('.el-tabs__item')]
      .find((e) => e.textContent?.trim() === '版本差异')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flush(200);
    await setDiff(1, 2);
    const list = q('diff-list')!;
    expect(q('diff-ADDED-finance')!.textContent).toContain('财务会签');
    expect(q('diff-ADDED-finance')!.textContent).toContain('会签');
    expect(q('diff-CHANGED-deptAudit')!.textContent).toContain('名称');
    expect(list.querySelectorAll('li')).toHaveLength(2);
    const canvases = host!.querySelectorAll('[data-testid="lf-canvas"]');
    const diffCanvas = canvases[canvases.length - 1] as HTMLElement;
    expect(diffCanvas.dataset.added).toBe('finance');
    expect(diffCanvas.dataset.changed).toBe('deptAudit');
    // 新增节点（及其解析、结果网关）在图上以绿色描边
    const greens = [...diffCanvas.querySelectorAll('rect, polygon, circle')].filter(
      (e) => e.getAttribute('stroke') === '#16a34a' && e.getAttribute('stroke-width') === '3'
    );
    expect(greens.length).toBeGreaterThanOrEqual(2);
  });

  it('BPMN 解析：多实例识别会签/或签，元素归属节点键', () => {
    const def: FlowDef = JSON.parse(JSON.stringify(v1));
    def.nodes.push({ key: 'fin', type: 'AUDIT', name: '财务', mode: 'AND' }, { key: 'ops', type: 'AUDIT', name: '运营', mode: 'OR' });
    const g = parseBpmn(bpmn(def));
    expect(g.nodes.find((n) => n.id === 'fin')!.multi).toBe('AND');
    expect(g.nodes.find((n) => n.id === 'ops')!.multi).toBe('OR');
    expect(g.nodes.find((n) => n.id === 'end_rejected')!.kind).toBe('terminate');
    const xs = ['start', 'initiator', 'resolve_deptAudit', 'deptAudit', 'gw_deptAudit'].map((id) => g.nodes.find((n) => n.id === id)!.x);
    expect([...xs].sort((a, b) => a - b)).toEqual(xs); // 从左到右分层
    const keys = new Set(['deptAudit', 'fin', 'ops']);
    expect(ownerKey('gw_fin', keys)).toBe('fin');
    expect(ownerKey('resolve_deptAudit', keys)).toBe('deptAudit');
    expect(ownerKey('end_approved', keys)).toBeUndefined();
    expect(diffDefs(v1, clean(def)).map((c) => c.kind + ':' + c.key)).toEqual(['ADDED:fin', 'ADDED:ops']);
  });
});

/** 在差异页选择基准与对比版本并比较。 */
async function setDiff(base: number, target: number) {
  const pick = async (testid: string, label: string) => {
    q(testid)!.querySelector<HTMLElement>('.el-select__wrapper')!.click();
    await flush(150);
    const popper = [...document.querySelectorAll('.el-select__popper')].find((p) => p.getAttribute('aria-hidden') === 'false')!;
    const item = [...popper.querySelectorAll('.el-select-dropdown__item')].find((e) => e.textContent!.startsWith(label)) as HTMLElement;
    item.click();
    await flush(400);
  };
  await pick('diff-base', `v${base}`);
  await pick('diff-target', `v${target}`);
  await click('btn-diff');
  await flush(400);
}
