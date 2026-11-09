/**
 * 11.4：协同应用中心——Pack 版本、依赖、负责人、复审、使用量、失败任务、漂移与建议下线状态；漂移差异与回灌；ChangeSet 阶段与审批。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import AppCenter from '@/views/lsmom/app-center/AppCenter.vue';

const pack = (code: string, extra: Record<string, any> = {}) => ({
  code,
  name: code.endsWith('training') ? '培训考试' : '客户扩展',
  version: '1.2.0',
  status: 'ENABLED',
  standard: code.endsWith('training'),
  owner: '王培训',
  requester: '李车间',
  reviewDue: '2026-12-31',
  reviewStatus: 'OK',
  dependsOn: [],
  dependents: [],
  entries: [{ name: '培训记录', path: '/lsmom/ledger/ls.training.Record' }],
  uses90d: 37,
  lastUsedAt: '2026-11-09T10:20:00',
  failedTasks: 0,
  latestVersion: '1.2.0',
  upgradeAvailable: false,
  drifted: false,
  driftCount: 0,
  items: [{ kind: 'VIEW', code: 'ls.training.recordForm', version: 1, path: 'views/record-form.json' }],
  changeSets: [],
  ...extra
});

function makeApi() {
  const packs = [
    pack('ls.training', { upgradeAvailable: true, latestVersion: '1.3.0', drifted: true, driftCount: 1 }),
    pack('acme.cust', {
      reviewStatus: 'SUGGEST_RETIRE',
      reviewDue: '2026-01-31',
      uses90d: 0,
      lastUsedAt: null,
      failedTasks: 2,
      dependsOn: [{ code: 'ls.training', version: '>=1.0.0 <2.0.0' }]
    })
  ];
  const changes = [
    {
      id: 7,
      pack: 'ls.training',
      title: '升级 ls.training 1.2.0 → 1.3.0',
      status: 'REVIEWING',
      breaking: false,
      source: 'PACK',
      items: [],
      artifactSha: 'a'.repeat(64),
      version: '1.3.0',
      createdAt: '2026-11-10T08:00:00',
      stages: ['VALIDATE', 'SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE'].map((s) => ({
        stage: s,
        status: 'PASSED',
        artifactSha: 'a'.repeat(64),
        detail: {},
        runAt: '2026-11-10T08:10:00'
      }))
    },
    {
      id: 8,
      pack: 'acme.cust',
      title: '升级 acme.cust 1.2.0 → 1.2.1',
      status: 'DRAFT',
      breaking: false,
      source: 'PACK',
      items: [],
      artifactSha: 'b'.repeat(64),
      version: '1.2.1',
      createdAt: '2026-11-10T09:00:00',
      stages: [
        { stage: 'VALIDATE', status: 'PASSED', artifactSha: 'b'.repeat(64), detail: {}, runAt: '2026-11-10T09:00:00' },
        {
          stage: 'UPGRADE',
          status: 'FAILED',
          artifactSha: 'b'.repeat(64),
          detail: { migration: 'V2__bad.sql', message: 'relation does not exist' },
          runAt: '2026-11-10T09:05:00'
        }
      ]
    }
  ];
  const api = {
    list: vi.fn(async () => packs),
    get: vi.fn(async (code: string) => packs.find((p) => p.code === code)!),
    enable: vi.fn(async () => undefined),
    disable: vi.fn(async () => undefined),
    exportData: vi.fn(async () => ({})),
    test: vi.fn(async () => ({
      pack: 'ls.training',
      version: '1.2.0',
      passed: true,
      regression: [{ name: '登记并完成培训', passed: true }],
      privilege: [{ name: '无权限新增 ls.training.Record', passed: true }]
    })),
    drift: vi.fn(async (code: string) => ({
      pack: code,
      version: '1.2.0',
      drifted: code === 'ls.training',
      items:
        code === 'ls.training'
          ? [
              {
                kind: 'VIEW',
                code: 'ls.training.recordForm',
                path: 'views/record-form.json',
                state: 'CHANGED',
                expectedVersion: 1,
                actualVersion: 1,
                diffs: [{ path: '/fields/1/label', expected: '学员', actual: '受训人' }]
              }
            ]
          : []
    })),
    driftToChangeSet: vi.fn(async () => ({ ...changes[0], id: 9, version: '1.2.1', source: 'DRIFT', status: 'DRAFT' })),
    runReview: vi.fn(async () => []),
    changeSets: vi.fn(async () => changes),
    gate: vi.fn(async () => ({ ready: true, problems: [] })),
    submit: vi.fn(async () => changes[0]),
    approve: vi.fn(async () => changes[0]),
    release: vi.fn(async () => ({ code: 'ls.training', version: '1.3.0', status: 'UPGRADED' }))
  };
  return api;
}

let app: App | undefined;
let host: HTMLElement | undefined;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay, .el-drawer__wrapper').forEach((e) => e.remove());
});

async function flush(ms = 80) {
  await nextTick();
  await new Promise((r) => setTimeout(r, ms));
  await nextTick();
}

const q = (id: string) => document.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;

async function mount() {
  const api = makeApi();
  host = document.createElement('div');
  document.body.appendChild(host);
  const ac = ref<any>();
  app = createApp({ setup: () => () => h(AppCenter, { ref: ac, api: api as any }) });
  app.use(ElementPlus);
  app.mount(host);
  await flush();
  return { api, ac };
}

describe('协同应用中心', () => {
  it('列表展示版本、待升级、依赖、负责人、复审、使用量、失败任务、漂移与建议下线', async () => {
    await mount();
    const rows = [...document.querySelectorAll('[data-testid="table-packs"] .el-table__body tr')] as HTMLElement[];
    expect(rows).toHaveLength(2);
    const training = rows.find((r) => r.textContent!.includes('ls.training') && r.textContent!.includes('培训考试'))!;
    expect(training.textContent).toContain('1.2.0');
    expect(training.textContent).toContain('可升级 1.3.0');
    expect(training.textContent).toContain('标准');
    expect(training.textContent).toContain('王培训');
    expect(training.textContent).toContain('2026-12-31');
    expect(training.querySelector('[data-testid="pack-uses"]')!.textContent).toBe('37');
    expect(training.querySelector('[data-testid="pack-drift"]')!.textContent).toContain('1 项');
    const cust = rows.find((r) => r.textContent!.includes('acme.cust'))!;
    expect(cust.querySelector('[data-testid="pack-review-status"]')!.textContent).toContain('建议下线');
    expect(cust.textContent).toContain('ls.training >=1.0.0 <2.0.0');
    expect(cust.textContent).toContain('客户');
  });

  it('详情：漂移差异可回灌为 ChangeSet，运行测试显示结果', async () => {
    const { api, ac } = await mount();
    await ac.value.open('ls.training');
    await flush(200);
    expect(q('pack-info')!.textContent).toContain('李车间');
    const table = q('drift-table')!;
    expect(table.textContent).toContain('/fields/1/label');
    expect(table.textContent).toContain('学员');
    expect(table.textContent).toContain('受训人');
    q('btn-drift-changeset')!.click();
    await flush(200);
    expect(api.driftToChangeSet).toHaveBeenCalledWith('ls.training');

    await ac.value.open('ls.training');
    await flush(200);
    q('btn-test')!.click();
    await flush(200);
    expect(q('test-result')!.textContent).toContain('全部通过');
  });

  it('建议下线的 Pack 在详情中提示', async () => {
    const { ac } = await mount();
    await ac.value.open('acme.cust');
    await flush(200);
    expect(q('retire-hint')!.textContent).toContain('建议下线');
  });

  it('变更发布：阶段状态、待审批可批准、失败阶段显示失败迁移', async () => {
    await mount();
    (document.querySelectorAll('.el-tabs__item')[1] as HTMLElement).click();
    await flush(200);
    expect(q('cs-status-7')!.textContent).toContain('待审批');
    expect(q('btn-approve-7')).not.toBeNull();
    expect(q('btn-approve-8')).toBeNull();
    expect(q('stage-8-UPGRADE')!.className).toContain('fail');
    expect(q('stage-8-SANDBOX')!.className).toContain('none');
    expect(q('stage-7-UPGRADE')!.className).toContain('ok');
    const expand = document.querySelectorAll('[data-testid="table-changes"] .el-table__expand-icon')[1] as HTMLElement;
    expand.click();
    await flush(200);
    expect(q('table-changes')!.textContent).toContain('迁移 V2__bad.sql');
  });
});
