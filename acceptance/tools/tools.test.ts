/** 验收工具自测：node --test tools/*.test.ts */
import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';
import { ACC, loadCases, type Case, type Scenario } from './lib.ts';
import { trace, failed, markdown } from './trace.ts';
import { validateCases } from './validate.ts';

const fx = (d: string) => path.join(ACC, 'tools/fixtures', d);

test('缺少关联场景、AUTO 缺脚本或脚本不存在时校验失败并指出编号', () => {
  const p = validateCases(loadCases(fx('bad')));
  assert.deepEqual(
    p.map((x) => x.id),
    ['AC-DEMO-001', 'AC-DEMO-002', 'AC-DEMO-003']
  );
  assert.match(p[0].message, /ref\.scenario/);
  assert.match(p[1].message, /script/);
  assert.match(p[2].message, /不存在/);
});

test('合法用例校验通过', () => {
  assert.deepEqual(validateCases(loadCases(fx('good'))), []);
});

const sc = (scenario: string, requirement = '角色化首页工作台'): Scenario => ({
  change: 'modernize-ui-ux',
  spec: 'ui/app-shell-navigation',
  requirement,
  scenario,
  text: ''
});
const kase = (id: string, scenario: string | string[], mode: 'AUTO' | 'MANUAL' = 'AUTO'): Case => ({
  id,
  title: id,
  ref: { change: 'modernize-ui-ux', spec: 'ui/app-shell-navigation', requirement: '角色化首页工作台', scenario },
  roles: ['班组长'],
  dataset: 'acceptance',
  steps: ['x'],
  expected: ['y'],
  mode,
  script: 'plus-ui/tests/ui/app/home.spec.ts'
});
const p0 = { p0: new Map([['ui/app-shell-navigation ▸ 角色化首页工作台', ['UI-HOME']]]), p2: new Set<string>() };

test('遗漏场景：报告列出所在 change、spec 与 Requirement', () => {
  const r = trace([sc('班组长首页'), sc('个人调整')], [kase('AC-X-001', '班组长首页')], p0);
  assert.equal(r.uncovered.length, 1);
  assert.equal(r.uncovered[0].scenario, '个人调整');
  assert.equal(r.uncovered[0].change, 'modernize-ui-ux');
  assert.ok(failed(r));
});

test('场景改名：旧名称引用报告为悬空，新名称报告为未覆盖', () => {
  const r = trace([sc('班组长首页（改名）')], [kase('AC-X-001', '班组长首页')], p0);
  assert.equal(r.dangling.length, 1);
  assert.equal(r.dangling[0].id, 'AC-X-001');
  assert.equal(r.uncovered.length, 1);
});

test('P0 Requirement 只有人工用例时报告缺 AUTO', () => {
  const r = trace([sc('班组长首页')], [kase('AC-X-001', '班组长首页', 'MANUAL')], p0);
  assert.deepEqual(r.p0MissingAuto, ['ui/app-shell-navigation ▸ 角色化首页工作台']);
  const ok = trace([sc('班组长首页')], [kase('AC-X-001', '班组长首页')], p0);
  assert.ok(!failed(ok));
});

test('一条用例可覆盖同一 Requirement 下的多个场景', () => {
  const r = trace([sc('班组长首页'), sc('个人调整')], [kase('AC-X-001', ['班组长首页', '个人调整'])], p0);
  assert.equal(r.covered, 2);
});

test('没有 openspec CLI 时解析 spec 文件，结果与 CLI 一致', async () => {
  const { loadScenarios, key } = await import('./lib.ts');
  const md = loadScenarios(undefined, 'markdown');
  let cli: typeof md;
  try {
    cli = loadScenarios(undefined, 'cli');
  } catch {
    return; // 本机没有 CLI 时只校验解析结果非空
  } finally {
    assert.ok(md.length > 0);
  }
  const k = (x: (typeof md)[number]) => key(x.spec, x.requirement, x.scenario);
  assert.deepEqual(md.map(k).sort(), cli!.map(k).sort());
});

test('人工检查表：生成 → 填写 → 导入，结果写入 results.json', async () => {
  const fs = await import('node:fs');
  const os = await import('node:os');
  const { generate, parse } = await import('./manual.ts');
  const { collect } = await import('./collect.ts');
  const c = {
    id: 'AC-DEMO-801',
    title: '人工示例',
    ref: { change: 'modernize-ui-ux', spec: 'ui/app-shell-navigation', requirement: '角色化首页工作台', scenario: '操作工首页' },
    priority: 'P1',
    mode: 'MANUAL',
    roles: ['operator'],
    dataset: 'acceptance',
    steps: ['登录', '查看首页'],
    expected: ['显示我的待办']
  } as unknown as Case;
  const md = generate({
    run: 'r',
    cases: [c],
    pages: [{ id: 'P-HOME', path: '/index', name: '首页', area: '工作台' }],
    heuristics: [
      { id: 'H-01', group: '可用性', name: '系统状态可见', judge: '操作有反馈' },
      { id: 'H-02', group: '可用性', name: '防错', judge: '危险操作需确认' }
    ],
    viewports: ['phone', 'desktop'],
    themes: ['dark']
  });
  assert.equal((md.match(/^### UI · /gm) ?? []).length, 2);
  let filled = md.replace('[ ] 失败', '[x] 失败').replace('- 走查员：', '- 走查员：张三').replace('- 说明：', '- 说明：待办数为 0');
  let n = 0;
  filled = filled.replace(/^\| (H-0\d) \| (.+?) \| (.+?) \|  \|  \|$/gm, (_, id, a, b) => `| ${id} | ${a} | ${b} | ${n++ === 1 ? '问题 | 删除未确认' : '通过 | '} |`);
  const r = parse(filled);
  assert.deepEqual(r.problems, []);
  assert.deepEqual(
    r.rows.map((x) => [x.caseId, x.result, x.tester, x.notes]),
    [['AC-DEMO-801', 'FAILED', '张三', '待办数为 0']]
  );
  assert.equal(r.uiChecks.length, 4);
  assert.deepEqual(r.uiChecks[1], { page: 'P-HOME', viewport: 'phone', theme: 'dark', path: '/index', item: 'H-02', name: '可用性：防错', result: 'ISSUE', note: '删除未确认' });
  assert.match(parse(filled.replace('删除未确认', '')).problems[0], /须写说明/);
  assert.match(parse(filled.replace('[ ] 通过', '[x] 通过')).problems[0], /勾选了多项/);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'acc-manual-'));
  fs.writeFileSync(path.join(dir, 'manual.json'), JSON.stringify({ rows: r.rows, uiChecks: r.uiChecks }));
  assert.deepEqual(collect(dir).rows.map((x) => [x.caseId, x.source, x.result]), [['AC-DEMO-801', 'manual', 'FAILED']]);
  fs.rmSync(dir, { recursive: true });
});

test('角色旅程：有功能用例的切片必须有旅程；旅程列出角色与经过的 spec', () => {
  const fn = { ...kase('AC-X-001', '班组长首页'), slice: 'A' };
  const r = trace([sc('班组长首页')], [fn], p0);
  assert.deepEqual(r.slicesWithoutJourney, ['A']);
  assert.ok(failed(r));
  const j = { ...kase('AC-J-A-001', '班组长首页'), id: 'AC-J-A-001', slice: 'A', journey: 'J-A', roles: ['班组长', '操作工'], covers: ['AC-X-001'] };
  const ok = trace([sc('班组长首页')], [fn, j], p0);
  assert.deepEqual(ok.slicesWithoutJourney, []);
  assert.deepEqual(ok.journeys, [{ id: 'AC-J-A-001', journey: 'J-A', slice: 'A', roles: ['班组长', '操作工'], specs: ['ui/app-shell-navigation'] }]);
  assert.ok(!failed(ok));
  assert.match(markdown(ok), /六个切片都有旅程|角色旅程：1 条/);
});
