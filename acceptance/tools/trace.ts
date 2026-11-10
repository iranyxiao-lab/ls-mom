/**
 * 追溯与覆盖门禁：从被验收 change 提取全部 (spec, requirement, scenario)，与用例库的 ref 比对，报告三类问题——
 *   未覆盖（场景没有任何用例）、悬空（用例引用的 Requirement / Scenario 已不存在或改名）、P0 缺 AUTO（P0 Requirement 没有自动用例）。
 * 运行：node tools/trace.ts [--report-only] [--md 输出.md] [--json 输出.json]
 *   有问题时退出码为 1；--report-only 只报告不阻断（CI 切换为阻断前使用）。
 */
import fs from 'node:fs';
import { key, loadCases, loadScenarios, priorities, reqKey, scenariosOf, type Case, type Scenario } from './lib.ts';

export interface TraceResult {
  scenarios: number;
  covered: number;
  uncovered: Scenario[];
  dangling: { id: string; file?: string; ref: string }[];
  p0: { requirement: string; codes: string[]; auto: number; manual: number }[];
  p0MissingAuto: string[];
  p0Unknown: string[];
  bySpec: Record<string, { change: string; scenarios: number; covered: number }>;
  /** 角色旅程（带 covers 的旅程用例）：切片、参与角色、经过的 spec */
  journeys: { id: string; journey: string; slice: string; roles: string[]; specs: string[] }[];
  /** 有功能用例但没有任何旅程的切片（A～F） */
  slicesWithoutJourney: string[];
}

export const SLICES = ['A', 'B', 'C', 'D', 'E', 'F'];

export function trace(scenarios: Scenario[], cases: Case[], prio = priorities()): TraceResult {
  const known = new Map(scenarios.map((s) => [key(s.spec, s.requirement, s.scenario), s]));
  const knownReq = new Set(scenarios.map((s) => reqKey(s.spec, s.requirement)));
  const coveredKeys = new Set<string>();
  const dangling: TraceResult['dangling'] = [];
  const byReq = new Map<string, Case[]>();
  for (const c of cases) {
    if (!c?.ref) continue;
    const rk = reqKey(c.ref.spec, c.ref.requirement);
    if (!byReq.has(rk)) byReq.set(rk, []);
    byReq.get(rk)!.push(c);
    for (const sc of scenariosOf(c)) {
      const k = key(c.ref.spec, c.ref.requirement, sc);
      if (known.has(k)) coveredKeys.add(k);
      else dangling.push({ id: c.id, file: c.file, ref: k });
    }
  }
  const uncovered = scenarios.filter((s) => !coveredKeys.has(key(s.spec, s.requirement, s.scenario)));
  const p0 = [...prio.p0].map(([rk, codes]) => {
    const list = byReq.get(rk) ?? [];
    return { requirement: rk, codes, auto: list.filter((c) => c.mode === 'AUTO').length, manual: list.filter((c) => c.mode === 'MANUAL').length };
  });
  const bySpec: TraceResult['bySpec'] = {};
  for (const s of scenarios) {
    const b = (bySpec[s.spec] ??= { change: s.change, scenarios: 0, covered: 0 });
    b.scenarios++;
    if (coveredKeys.has(key(s.spec, s.requirement, s.scenario))) b.covered++;
  }
  const byId = new Map(cases.map((c) => [c.id, c]));
  const journeys = cases
    .filter((c) => c?.journey && (c.covers ?? []).length)
    .map((c) => ({
      id: c.id,
      journey: c.journey!,
      slice: c.slice ?? '',
      roles: c.roles,
      specs: [...new Set([c.ref.spec, ...(c.covers ?? []).map((id) => byId.get(id)?.ref.spec).filter((x): x is string => !!x)])]
    }));
  return {
    journeys,
    // 只要求用例库中出现过的切片（有该切片的功能用例）都有旅程
    slicesWithoutJourney: SLICES.filter((sl) => cases.some((c) => c?.slice === sl) && !journeys.some((j) => j.slice === sl)),
    scenarios: scenarios.length,
    covered: scenarios.length - uncovered.length,
    uncovered,
    dangling,
    p0,
    p0MissingAuto: p0.filter((r) => knownReq.has(r.requirement) && r.auto === 0).map((r) => r.requirement),
    p0Unknown: p0.filter((r) => !knownReq.has(r.requirement)).map((r) => r.requirement),
    bySpec
  };
}

export const failed = (r: TraceResult) =>
  r.uncovered.length + r.dangling.length + r.p0MissingAuto.length + r.p0Unknown.length + r.slicesWithoutJourney.length > 0;

export function markdown(r: TraceResult): string {
  const pct = r.scenarios ? ((r.covered / r.scenarios) * 100).toFixed(1) : '0';
  const lines = [
    '## 验收覆盖报告',
    '',
    `- 场景：${r.scenarios}，已覆盖 ${r.covered}（${pct}%），未覆盖 ${r.uncovered.length}`,
    `- 悬空引用：${r.dangling.length}`,
    `- P0 Requirement：${r.p0.length}，缺 AUTO 用例 ${r.p0MissingAuto.length}${r.p0Unknown.length ? `，优先级清单中不存在 ${r.p0Unknown.length}` : ''}`,
    `- 角色旅程：${r.journeys.length} 条${r.slicesWithoutJourney.length ? `，切片 ${r.slicesWithoutJourney.join('、')} 没有旅程` : '，六个切片都有旅程'}`,
    `- 结论：${failed(r) ? '**未通过**' : '通过'}`,
    ''
  ];
  const section = (title: string, rows: string[], limit = 300) => {
    if (!rows.length) return;
    lines.push(`### ${title}（${rows.length}）`, '');
    rows.slice(0, limit).forEach((x) => lines.push(`- ${x}`));
    if (rows.length > limit) lines.push(`- ……另有 ${rows.length - limit} 项`);
    lines.push('');
  };
  section('未覆盖场景', r.uncovered.map((s) => `\`${s.change}\` ${key(s.spec, s.requirement, s.scenario)}`));
  section('悬空引用', r.dangling.map((d) => `${d.id}（${d.file}）→ ${d.ref}`));
  section('P0 缺 AUTO 用例', r.p0MissingAuto);
  section('优先级清单中不存在的 Requirement', r.p0Unknown);
  if (r.journeys.length) {
    lines.push('### 角色旅程', '', '| 编号 | 切片 | 参与角色 | 经过的 spec |', '|---|---|---|---|');
    for (const j of [...r.journeys].sort((a, b) => a.slice.localeCompare(b.slice) || a.id.localeCompare(b.id)))
      lines.push(`| ${j.id}（${j.journey}） | ${j.slice} | ${j.roles.join('、')} | ${j.specs.join('、')} |`);
    lines.push('');
  }
  section('没有旅程的切片', r.slicesWithoutJourney);
  lines.push('### 按 spec', '', '| change | spec | 场景 | 已覆盖 |', '|---|---|---|---|');
  for (const [spec, b] of Object.entries(r.bySpec).sort()) lines.push(`| ${b.change} | ${spec} | ${b.scenarios} | ${b.covered} |`);
  return lines.join('\n') + '\n';
}

if (import.meta.main) {
  const args = process.argv.slice(2);
  const opt = (name: string) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
  const r = trace(loadScenarios(), loadCases());
  const md = markdown(r);
  if (opt('--md')) fs.writeFileSync(opt('--md')!, md);
  if (opt('--json')) fs.writeFileSync(opt('--json')!, JSON.stringify(r, null, 2));
  console.log(md);
  if (failed(r) && !args.includes('--report-only')) process.exit(1);
}
