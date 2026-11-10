/** 缺陷、报告、签收与用例执行工具自测：node --test tools/*.test.ts */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import type { Case, Scenario } from './lib.ts';
import type { ResultRow } from './collect.ts';
import { fetchDefects, fixesOf, labelsFor, openDefectsByScenario, parseDefect, regressionCases, regressionComment, type Defect } from './defects.ts';
import { build, html, markdown, type Inputs } from './report.ts';
import { runAuto, surefire, targetOf } from './run-auto.ts';
import { changedSignoffs } from './signoff-guard.ts';

const body = (o: Record<string, string>) => Object.entries(o).map(([k, v]) => `### ${k}\n\n${v}\n`).join('\n');
const issue = (number: number, sev: string, extra: Partial<{ state: string; labels: string[]; cases: string; ref: string }> = {}) => ({
  number,
  title: `缺陷 ${number}`,
  state: extra.state ?? 'OPEN',
  url: `https://github.com/o/ls-mom/issues/${number}`,
  labels: ['defect', ...(extra.labels ?? [])].map((name) => ({ name })),
  body: body({
    严重级别: sev,
    类型: '安全',
    关联用例编号: extra.cases ?? 'AC-FLOW-WC-012',
    '关联 spec 与 Scenario': extra.ref ?? 'bootstrap-ls-mom-mvp ▸ flow/workflow-collaboration ▸ 任务办理权限 ▸ 非办理人办理',
    复现步骤: '1. acc_team 登录\n2. 办理 acc_leader 的待办',
    预期结果: '拒绝',
    实际结果: '办理成功',
    环境与版本: 'runs/x',
    证据: '_No response_'
  })
});
const mkCase = (id: string, o: Partial<Case> = {}): Case =>
  ({
    id,
    title: id,
    ref: { change: 'bootstrap-ls-mom-mvp', spec: 'flow/workflow-collaboration', requirement: '任务办理权限', scenario: '非办理人办理' },
    roles: ['班组长'],
    dataset: 'acceptance',
    steps: ['x'],
    expected: ['y'],
    mode: 'AUTO',
    script: 'ls-mom-modules/ls-mom-flow/src/test/java/com/lsmom/flow/FlowAuthTest.java#非办理人',
    ...o
  }) as Case;

test('缺陷表单解析：定级、类型、用例编号、spec 与 Scenario，补打标签', () => {
  const d = parseDefect(issue(7, 'S1 阻断（核心流程走不通、数据错误、越权、安全问题）'));
  assert.equal(d.severity, 'S1');
  assert.equal(d.type, 'security');
  assert.deepEqual(d.caseIds, ['AC-FLOW-WC-012']);
  assert.deepEqual(d.specs, ['flow/workflow-collaboration']);
  assert.equal(d.scenario, '非办理人办理');
  assert.deepEqual(labelsFor(d), ['defect', 'S1', 'type/security', 'spec/flow/workflow-collaboration']);
  // 复核后改级以标签为准
  assert.equal(parseDefect(issue(7, 'S1 阻断', { labels: ['S2'] })).severity, 'S2');
});

test('gh 不可用时缺陷标注为未同步', () => {
  const r = fetchDefects(() => {
    throw Object.assign(new Error('x'), { stderr: 'error connecting to api.github.com' });
  });
  assert.equal(r.synced, false);
  assert.match(r.error!, /api\.github\.com/);
  const ok = fetchDefects(() => JSON.stringify([issue(2, 'S3 一般'), issue(1, 'S2 严重')]));
  assert.deepEqual(ok.defects.map((d) => [d.number, d.severity]), [[1, 'S2'], [2, 'S3']]);
});

test('回归：PR 正文 Fixes #n、回归范围含同一旅程、失败评论', () => {
  assert.deepEqual(fixesOf('修复越权\n\nFixes #12, closes #3 and resolves #12'), [12, 3]);
  const d = parseDefect(issue(12, 'S1'));
  const cases = [mkCase('AC-FLOW-WC-012', { journey: 'J-B' } as any), mkCase('AC-J-B-001', { journey: 'J-B' } as any), mkCase('AC-OTHER-001')];
  assert.deepEqual(regressionCases(d, cases).map((c) => c.id), ['AC-FLOW-WC-012', 'AC-J-B-001']);
  const rows: ResultRow[] = [{ caseId: 'AC-FLOW-WC-012', title: '', source: 'auto', result: 'FAILED', durationMs: 1200, evidence: [], error: '仍可办理' }];
  const c = regressionComment(d, rows, 'regress-1');
  assert.equal(c.ok, false);
  assert.match(c.body, /回归未通过[\s\S]*AC-FLOW-WC-012 \| FAILED[\s\S]*已重新打开/);
  assert.equal(regressionComment(d, [], 'r').ok, false);
});

const scenario: Scenario = { change: 'bootstrap-ls-mom-mvp', spec: 'flow/workflow-collaboration', requirement: '任务办理权限', scenario: '非办理人办理' } as Scenario;
const pass = (id: string): ResultRow => ({ caseId: id, title: id, source: 'auto', result: 'PASSED', durationMs: 1, evidence: [] });
const inputs = (defects: Defect[]): Inputs => ({
  run: '20261111-1000-abc1234',
  commit: 'abc1234',
  changes: ['bootstrap-ls-mom-mvp'],
  cases: [mkCase('AC-FLOW-WC-012', { priority: 'P0' })],
  scenarios: [scenario],
  results: [pass('AC-FLOW-WC-012')],
  defects: { synced: true, fetchedAt: '', defects },
  budgets: [{ id: 'UI-CLICK-REPORT', name: '移动报工', kind: 'click', budget: 5, actual: 4, unit: '次' }],
  a11y: { pages: 30, critical: 0, serious: 0 },
  usability: null,
  deferred: [],
  p0Requirements: new Set()
});

test('报告：其余达标但有 1 个未关闭 S2 时结论为"不通过"并给出原因，场景标记有未关闭缺陷', () => {
  const s2 = parseDefect(issue(5, 'S2 严重', { cases: 'AC-FLOW-WC-012' }));
  const r = build(inputs([s2]));
  assert.equal(r.passed, false);
  assert.deepEqual(r.reasons, ['有 1 个未关闭的 S2 缺陷：#5']);
  const md = markdown(r);
  assert.match(md, /## 结论：不通过/);
  assert.match(md, /`abc1234`/);
  assert.match(md, /有未关闭缺陷的场景[\s\S]*非办理人办理 \| \[#5\]\(https:\/\/github\.com\/o\/ls-mom\/issues\/5\) S2/);
  const h = html(md, 'r', false);
  assert.match(h, /class="verdict fail"/);
  assert.match(h, /<a href="https:\/\/github.com\/o\/ls-mom\/issues\/5">#5<\/a>/);
  // 关闭后通过
  assert.equal(build(inputs([{ ...s2, state: 'CLOSED' }])).passed, true);
});

test('报告：S3 无处理决定、预算超标、缺陷未同步、P0 未执行都判不通过', () => {
  const s3 = parseDefect(issue(6, 'S3 一般'));
  const i = inputs([s3]);
  i.budgets![0].actual = 7;
  i.results = [];
  const r = build(i);
  assert.equal(r.passed, false);
  assert.ok(r.reasons.some((x) => x.includes('#6') && x.includes('处理决定')));
  assert.ok(r.reasons.some((x) => x.includes('移动报工 7次')));
  assert.ok(r.reasons.some((x) => x.startsWith('P0 用例未全部通过')));
  assert.ok(r.reasons.some((x) => x.includes('通过率 0%')));
  assert.equal(build(inputs([{ ...s3, decision: 'defer' }])).passed, true);
  const un = inputs([]);
  un.defects = { synced: false, fetchedAt: '', error: 'gh 未登录', defects: [] };
  assert.match(build(un).reasons[0], /缺陷数据未同步（gh 未登录）/);
  assert.ok(openDefectsByScenario([s3], i.cases).size === 1);
});

test('签收记录只增不改：修改历史签收文件被检出，新增与模板改动放行', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'acc-signoff-'));
  const git = (...a: string[]) => execFileSync('git', ['-c', 'user.email=t@t', '-c', 'user.name=t', ...a], { cwd: dir, encoding: 'utf8' });
  git('init', '-q');
  fs.mkdirSync(path.join(dir, 'acceptance/signoff'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'acceptance/signoff/20261111-abc1234.md'), '# 签收\n');
  git('add', '.');
  git('commit', '-qm', 'a');
  const base = git('rev-parse', 'HEAD').trim();
  fs.writeFileSync(path.join(dir, 'acceptance/signoff/20261201-def5678.md'), '# 新签收\n');
  fs.writeFileSync(path.join(dir, 'acceptance/signoff/TEMPLATE.md'), '# 模板\n');
  git('add', '.');
  git('commit', '-qm', 'b');
  assert.deepEqual(changedSignoffs(base, 'HEAD', dir), []);
  fs.appendFileSync(path.join(dir, 'acceptance/signoff/20261111-abc1234.md'), '改了\n');
  git('commit', '-qam', 'c');
  assert.deepEqual(changedSignoffs(base, 'HEAD', dir), [{ status: 'M', file: 'acceptance/signoff/20261111-abc1234.md' }]);
  fs.rmSync(dir, { recursive: true });
});

test('用例执行：按脚本类型分派，端到端脚本按检查项片段判定，Surefire 报告解析', () => {
  const kinds = ['a/src/test/java/X.java#m', 'plus-ui/tests/e2e/a.e2e.mjs#x', 'plus-ui/tests/ui/app/h.spec.ts', 'plus-ui/src/a.test.ts', 'acceptance/tools/t.test.ts', 'acceptance/e2e/j.spec.ts'].map(
    (s) => targetOf(mkCase('AC-X-001', { script: s }))!.kind
  );
  assert.deepEqual(kinds, ['java', 'e2e', 'pw', 'vitest', 'node', 'walk']);
  assert.equal(targetOf(mkCase('AC-X-001', { mode: 'MANUAL', script: undefined })), null);
  const xml = '<testsuite><testcase name="通过()" classname="X"/><testcase name="失败" classname="X"><failure message="期望 1"/></testcase><testcase name="跳过"><skipped/></testcase></testsuite>';
  assert.deepEqual([...surefire(xml)], [['通过', null], ['失败', '期望 1'], ['跳过', 'SKIPPED']]);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'acc-auto-'));
  const calls: string[] = [];
  const rows = runAuto(
    [mkCase('AC-E-001', { script: 'plus-ui/tests/e2e/a.e2e.mjs#审批通过' }), mkCase('AC-E-002', { script: 'plus-ui/tests/e2e/a.e2e.mjs#不存在的检查' })],
    dir,
    { exec: (cmd, args) => (calls.push([cmd, ...args].join(' ')), { status: 0, out: '✓ 审批通过后状态为已发布\n' }), log: () => {} }
  );
  assert.equal(calls.length, 1, '同一脚本只执行一次');
  assert.deepEqual(rows.map((r) => [r.caseId, r.result]), [['AC-E-001', 'PASSED'], ['AC-E-002', 'FAILED']]);
  assert.match(rows[1].error!, /没有检查项/);
  assert.ok(fs.existsSync(path.join(dir, rows[0].evidence[0])));
  fs.rmSync(dir, { recursive: true });
});
