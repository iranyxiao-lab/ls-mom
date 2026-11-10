/** 验收工具自测：node --test tools/*.test.ts */
import assert from 'node:assert/strict';
import path from 'node:path';
import { test } from 'node:test';
import { ACC, loadCases, type Case, type Scenario } from './lib.ts';
import { trace, failed } from './trace.ts';
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
