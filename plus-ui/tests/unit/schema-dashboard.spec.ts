/**
 * 11.2：看板组件只绑定指标/命名 Query；指标卡显示值与口径版本，点击钻取到参与计算的明细；图表点击按单元格钻取；时间范围计算。
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { createApp, h, nextTick, ref, type App } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import SchemaDashboard from '@/components/SchemaDashboard/index.vue';
import { rangeOf, formatValue } from '@/components/SchemaDashboard/range';
import type { DashboardSchema } from '@/types/lsView';

const issues = Array.from({ length: 12 }, (_, i) => ({ id: i + 1, code: `IS${i + 1}`, title: `本周异常${i}`, severity: i % 3 ? 'LOW' : 'HIGH' }));

function makeApi() {
  return {
    list: vi.fn(),
    get: vi.fn(),
    query: vi.fn(async (code: string, q: any) => {
      if (code === 'ls.core.workOnTimeRate')
        return {
          code,
          version: 2,
          name: '按期完成率',
          unit: '%',
          fn: 'ratio',
          grain: 'WEEK',
          groupBy: [],
          rows: [
            { bucket: '2026-11-02', value: 0.5, numerator: 1, denominator: 2 },
            { bucket: '2026-11-09', value: 0.75, numerator: 3, denominator: 4 }
          ]
        };
      if (q.groupBy?.length)
        return {
          code,
          version: 1,
          name: '异常数',
          unit: '条',
          fn: 'count',
          groupBy: q.groupBy,
          rows: [
            { severity: 'HIGH', value: 4 },
            { severity: 'LOW', value: 8 }
          ]
        };
      return { code, version: 1, name: '异常数', unit: '条', fn: 'count', groupBy: [], rows: [{ value: 12 }] };
    }),
    drill: vi.fn(async (code: string, r: any) => {
      const rows = r.dims?.severity ? issues.filter((x) => x.severity === r.dims.severity) : issues;
      return { code, version: r.version ?? 1, concept: 'ls.core.Issue', total: rows.length, columns: ['id', 'code', 'title', 'severity'], rows };
    }),
    namedQuery: vi.fn(async (code: string) => ({
      code,
      version: 3,
      columns: ['org_name', 'open_count'],
      rows: [{ org_name: '甲班', open_count: 5 }],
      truncated: false
    }))
  };
}

const schema: DashboardSchema = {
  viewType: 'DASHBOARD',
  formCode: 'acme.qa.board',
  version: 1,
  title: '质量看板',
  widgets: [
    { type: 'metricCard', title: '本周异常数', metric: 'ls.core.issueCount', range: 'thisWeek' },
    { type: 'trend', title: '按期完成率', metric: 'ls.core.workOnTimeRate', grain: 'WEEK', range: 'last30Days' },
    { type: 'bar', title: '按严重度', metric: 'ls.core.issueCount', dimensions: ['severity'], range: 'thisMonth' },
    { type: 'table', title: '未关闭', query: 'ls.core.openIssuesByOrg', params: { severity: 'HIGH' } }
  ]
};

let app: App | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  app?.unmount();
  host?.remove();
  document.querySelectorAll('.el-overlay').forEach((e) => e.remove());
});
async function flush(ms = 80) {
  for (let i = 0; i < 5; i++) await nextTick();
  await new Promise((r) => setTimeout(r, ms));
}
const q = (id: string) => (host!.querySelector(`[data-testid="${id}"]`) ?? document.querySelector(`[data-testid="${id}"]`)) as HTMLElement | null;

describe('看板渲染器', () => {
  it('指标卡显示值与口径版本，点击钻取到 12 条明细', async () => {
    const api = makeApi();
    host = document.createElement('div');
    host.style.width = '1400px';
    document.body.appendChild(host);
    const now = new Date(2026, 10, 12); // 周四
    const comp = ref<any>();
    app = createApp({ setup: () => () => h(SchemaDashboard, { ref: comp, schema, api, now }) });
    app.use(ElementPlus);
    app.mount(host);
    await flush(300);

    expect(q('widget-value-0')!.textContent).toContain('12');
    expect(q('widget-0')!.textContent).toContain('ls.core.issueCount · v1');
    expect(api.query).toHaveBeenCalledWith('ls.core.issueCount', expect.objectContaining({ from: '2026-11-09', to: '2026-11-16', groupBy: [] }));
    // 趋势：比率按百分比，带粒度
    expect(api.query).toHaveBeenCalledWith('ls.core.workOnTimeRate', expect.objectContaining({ grain: 'WEEK', from: '2026-10-14' }));
    expect(q('widget-chart-1')!.querySelector('canvas')).not.toBeNull();
    // 命名 Query 表格
    expect(api.namedQuery).toHaveBeenCalledWith('ls.core.openIssuesByOrg', { severity: 'HIGH' });
    expect(q('widget-table-3')!.textContent).toContain('甲班');
    expect(q('widget-3')!.textContent).toContain('命名 Query ls.core.openIssuesByOrg · v3');
    // 看板不发送任何 SQL
    const sent = JSON.stringify([...api.query.mock.calls, ...api.namedQuery.mock.calls]);
    expect(sent.toLowerCase()).not.toMatch(/select\s/);

    q('widget-value-0')!.click();
    await flush(200);
    expect(api.drill).toHaveBeenCalledWith('ls.core.issueCount', expect.objectContaining({ from: '2026-11-09', to: '2026-11-16', version: 1 }));
    expect(q('drill-total')!.textContent).toBe('12');
    expect(q('drill-meta')!.textContent).toContain('ls.core.issueCount');
    expect(q('drill-meta')!.textContent).toContain('口径 v1');
    expect(document.querySelectorAll('[data-testid="drill-table"] .el-table__body tr')).toHaveLength(12);
  });

  it('柱状图点击某一柱按维度钻取', async () => {
    const api = makeApi();
    host = document.createElement('div');
    host.style.width = '1400px';
    document.body.appendChild(host);
    const comp = ref<any>();
    app = createApp({ setup: () => () => h(SchemaDashboard, { ref: comp, schema: { ...schema, widgets: [schema.widgets[2]] }, api }) });
    app.use(ElementPlus);
    app.mount(host);
    await flush(300);
    const echarts = await import('echarts');
    const chart = echarts.getInstanceByDom(q('widget-chart-0')!)!;
    expect(chart).toBeTruthy();
    (chart as any).trigger('click', { dataIndex: 0, name: 'HIGH' });
    await flush(200);
    expect(api.drill).toHaveBeenCalledWith('ls.core.issueCount', expect.objectContaining({ dims: { severity: 'HIGH' } }));
    expect(q('drill-total')!.textContent).toBe('4');
  });

  it('时间范围与数值格式', () => {
    const now = new Date(2026, 10, 10); // 周二
    expect(rangeOf('thisWeek', now)).toEqual({ from: '2026-11-09', to: '2026-11-16' });
    expect(rangeOf('today', now)).toEqual({ from: '2026-11-10', to: '2026-11-11' });
    expect(rangeOf('thisMonth', now)).toEqual({ from: '2026-11-01', to: '2026-12-01' });
    expect(rangeOf('last7Days', now)).toEqual({ from: '2026-11-04', to: '2026-11-11' });
    expect(rangeOf('all', now)).toEqual({});
    expect(formatValue(0.6667, 'ratio')).toBe('66.7%');
    expect(formatValue(12, 'count', '条')).toBe('12 条');
    expect(formatValue(null, 'count')).toBe('—');
  });
});
