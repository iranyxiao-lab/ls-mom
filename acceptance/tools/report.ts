/**
 * 验收报告（acceptance-walkthrough 3.4）：汇总本轮 runs/<run>/ 下的
 *   results.json（collect.ts）、defects.json（defects.ts sync）、budgets.json（操作与性能预算实测，e2e/ux/ops-cost.spec.ts）、
 *   a11y.json（无障碍扫描汇总）、usability.json（可用性测试记录），以及 deferred.yaml（延期项）与覆盖追溯，
 * 生成 report.md 与单文件 report.html，并按准出条件自动判定"通过／不通过"及原因。
 * 运行：node tools/report.ts <run> [--commit <sha>]；结论为"不通过"时退出码 1。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';
import { ACC, config, loadCases, loadScenarios, priorities, reqKey, ROOT, type Case, type Scenario } from './lib.ts';
import { runDirOf, type ResultRow } from './collect.ts';
import { openDefectsByScenario, SEVERITIES, type Defect, type DefectSync, type Severity } from './defects.ts';
import { trace, type TraceResult } from './trace.ts';

export interface Budget {
  id: string;
  name: string;
  kind: 'click' | 'time' | 'perf';
  /** null：spec 未规定预算，只记录 */
  budget: number | null;
  actual: number | null;
  unit: string;
}
export interface A11y {
  pages: number;
  critical: number;
  serious: number;
  issues?: { page: string; rule: string; impact: string }[];
}
export interface Usability {
  tasks: { task: string; role: string; participants: number; completed: number; medianSec?: number }[];
}
export interface Deferred {
  item: string;
  reason: string;
  plan?: string;
}
export interface Inputs {
  run: string;
  commit: string;
  changes: string[];
  cases: Case[];
  scenarios: Scenario[];
  results: ResultRow[];
  defects: DefectSync | null;
  budgets: Budget[] | null;
  a11y: A11y | null;
  usability: Usability | null;
  deferred: Deferred[];
  p0Requirements?: Set<string>;
}

export interface Report {
  run: string;
  commit: string;
  passed: boolean;
  reasons: string[];
  metrics: {
    total: number;
    executed: number;
    passed: number;
    passRate: number;
    p0: number;
    p0Passed: number;
    open: Record<Severity, number>;
  };
  latest: Map<string, ResultRow>;
  trace: TraceResult;
  bySlice: { slice: string; cases: number; passed: number }[];
  scenarioDefects: Map<string, Defect[]>;
  inputs: Inputs;
}

const pct = (a: number, b: number) => (b ? Math.round((a / b) * 1000) / 10 : 0);
const ok = (b: Budget) => b.actual !== null && (b.budget === null || b.actual <= b.budget);

/** 每条用例取最后一次结果（同一 run 中补跑的结果覆盖先前的） */
function latestOf(rows: ResultRow[]) {
  const m = new Map<string, ResultRow>();
  for (const r of [...rows].sort((a, b) => (a.startedAt ?? '').localeCompare(b.startedAt ?? ''))) m.set(r.caseId, r);
  return m;
}

export function isP0(c: Case, p0Req: Set<string>) {
  return c.priority === 'P0' || p0Req.has(reqKey(c.ref.spec, c.ref.requirement));
}

export function build(i: Inputs): Report {
  const p0Req = i.p0Requirements ?? new Set(priorities().p0.keys());
  const latest = latestOf(i.results.filter((r) => i.cases.some((c) => c.id === r.caseId)));
  const passedIds = [...latest.values()].filter((r) => r.result === 'PASSED').map((r) => r.caseId);
  const p0Cases = i.cases.filter((c) => isP0(c, p0Req));
  const p0Passed = p0Cases.filter((c) => latest.get(c.id)?.result === 'PASSED');
  const defects = i.defects?.defects ?? [];
  const open = defects.filter((d) => d.state === 'OPEN');
  const count = (s: Severity) => open.filter((d) => d.severity === s).length;
  const metrics = {
    total: i.cases.length,
    executed: latest.size,
    passed: passedIds.length,
    passRate: pct(passedIds.length, i.cases.length),
    p0: p0Cases.length,
    p0Passed: p0Passed.length,
    open: { S1: count('S1'), S2: count('S2'), S3: count('S3'), S4: count('S4') }
  };

  const reasons: string[] = [];
  const link = (d: Defect) => `#${d.number}`;
  if (!i.defects?.synced) reasons.push(`缺陷数据未同步${i.defects?.error ? `（${i.defects.error}）` : ''}，无法确认 S1、S2 是否为零`);
  for (const s of ['S1', 'S2'] as Severity[]) {
    const l = open.filter((d) => d.severity === s);
    if (l.length) reasons.push(`有 ${l.length} 个未关闭的 ${s} 缺陷：${l.map(link).join('、')}`);
  }
  const unrated = open.filter((d) => !d.severity);
  if (unrated.length) reasons.push(`有 ${unrated.length} 个未定级的缺陷：${unrated.map(link).join('、')}`);
  const s3 = open.filter((d) => d.severity === 'S3' && !d.decision);
  if (s3.length) reasons.push(`有 ${s3.length} 个 S3 缺陷没有处理决定（decision/fix 或 decision/defer）：${s3.map(link).join('、')}`);
  if (p0Passed.length < p0Cases.length) {
    const bad = p0Cases.filter((c) => latest.get(c.id)?.result !== 'PASSED');
    reasons.push(`P0 用例未全部通过：${bad.length} 条（${bad.slice(0, 10).map((c) => `${c.id} ${latest.get(c.id)?.result ?? '未执行'}`).join('、')}${bad.length > 10 ? ' …' : ''}）`);
  }
  if (metrics.passRate < 95) reasons.push(`全部用例通过率 ${metrics.passRate}%，低于 95%（${metrics.passed}/${metrics.total}，未执行按未通过计）`);
  if (!i.budgets?.length) reasons.push('没有操作与性能预算实测结果（budgets.json）');
  else {
    const over = i.budgets.filter((b) => !ok(b));
    if (over.length) reasons.push(`预算未达标：${over.map((b) => `${b.name} ${b.actual ?? '未测'}${b.unit}（预算 ${b.budget === null ? '只记录' : b.budget + b.unit}）`).join('、')}`);
  }
  if (!i.a11y) reasons.push('没有无障碍扫描结果（a11y.json）');
  else if (i.a11y.critical + i.a11y.serious > 0) reasons.push(`无障碍扫描有严重问题：critical ${i.a11y.critical}、serious ${i.a11y.serious}`);
  if (i.usability) {
    const low = i.usability.tasks.filter((t) => t.participants > 0 && t.completed / t.participants < 0.8);
    if (low.length) reasons.push(`可用性测试完成率低于 80%：${low.map((t) => `${t.role}·${t.task} ${pct(t.completed, t.participants)}%`).join('、')}`);
  }

  const tr = trace(i.scenarios, i.cases, { p0: new Map([...p0Req].map((r) => [r, []])), p2: new Set() });
  const slices = [...new Set(i.cases.map((c) => c.slice ?? '未分切片'))].sort();
  const bySlice = slices.map((s) => {
    const l = i.cases.filter((c) => (c.slice ?? '未分切片') === s);
    return { slice: s, cases: l.length, passed: l.filter((c) => latest.get(c.id)?.result === 'PASSED').length };
  });
  return { run: i.run, commit: i.commit, passed: reasons.length === 0, reasons, metrics, latest, trace: tr, bySlice, scenarioDefects: openDefectsByScenario(defects, i.cases), inputs: i };
}

const esc = (s: string) => String(s ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ');

export function markdown(r: Report): string {
  const { metrics: m, inputs: i } = r;
  const o: string[] = [];
  o.push(`# 验收报告 · ${r.run}`, '');
  o.push(`- 范围：${i.changes.join('、')}`, `- 提交号：\`${r.commit}\``, `- 生成时间：${new Date().toISOString()}`, '');
  o.push(`## 结论：${r.passed ? '通过' : '不通过'}`, '');
  if (r.reasons.length) for (const x of r.reasons) o.push(`- ${x}`);
  else o.push('- 全部准出条件达标');
  o.push('', '## 执行与通过率', '');
  o.push('| 指标 | 数值 |', '|---|---|');
  o.push(`| 用例总数 | ${m.total} |`, `| 已执行 | ${m.executed} |`, `| 通过 | ${m.passed} |`, `| 通过率 | ${m.passRate}% |`, `| P0 通过 | ${m.p0Passed}/${m.p0} |`);
  o.push('', '### 按切片', '', '| 切片 | 用例 | 通过 |', '|---|---|---|');
  for (const s of r.bySlice) o.push(`| ${s.slice} | ${s.cases} | ${s.passed} |`);
  o.push('', '## 覆盖', '', `场景 ${r.trace.covered}/${r.trace.scenarios}（${pct(r.trace.covered, r.trace.scenarios)}%），悬空引用 ${r.trace.dangling.length}，P0 缺 AUTO ${r.trace.p0MissingAuto.length}`, '');
  o.push('| spec | change | 场景 | 已覆盖 | 未关闭缺陷 |', '|---|---|---|---|---|');
  for (const [spec, b] of Object.entries(r.trace.bySpec).sort()) {
    const ds = [...r.scenarioDefects].filter(([k]) => k.startsWith(spec + ' ▸ ')).flatMap(([, l]) => l);
    o.push(`| ${spec} | ${b.change} | ${b.scenarios} | ${b.covered} | ${[...new Set(ds)].map((d) => `[#${d.number}](${d.url}) ${d.severity ?? ''}`).join(' ') || '—'} |`);
  }
  if (r.scenarioDefects.size) {
    o.push('', '### 有未关闭缺陷的场景', '', '| 场景 | 缺陷 |', '|---|---|');
    for (const [k, l] of r.scenarioDefects) o.push(`| ${esc(k)} | ${l.map((d) => `[#${d.number}](${d.url}) ${d.severity ?? '未定级'} ${esc(d.title)}`).join('<br>')} |`);
  }
  o.push('', '## 缺陷', '');
  if (!i.defects?.synced) o.push(`> 缺陷数据未同步${i.defects?.error ? `：${i.defects.error}` : ''}`, '');
  o.push('| 级别 | 未关闭 |', '|---|---|');
  for (const s of ['S1', 'S2', 'S3', 'S4'] as Severity[]) o.push(`| ${s} ${SEVERITIES[s]} | ${m.open[s]} |`);
  const ds = i.defects?.defects ?? [];
  if (ds.length) {
    o.push('', '| 编号 | 级别 | 类型 | 状态 | 用例 | 标题 | 处理决定 |', '|---|---|---|---|---|---|---|');
    for (const d of ds) o.push(`| [#${d.number}](${d.url}) | ${d.severity ?? '未定级'} | ${d.type ?? ''} | ${d.state === 'OPEN' ? '未关闭' : '已关闭'} | ${d.caseIds.join(' ')} | ${esc(d.title)} | ${d.decision === 'fix' ? '修复' : d.decision === 'defer' ? '延期' : ''} |`);
  }
  o.push('', '## 操作与性能预算', '');
  if (!i.budgets?.length) o.push('未实测。');
  else {
    o.push('| 项目 | 类型 | 预算 | 实测 | 结果 |', '|---|---|---|---|---|');
    for (const b of i.budgets) o.push(`| ${esc(b.name)} | ${{ click: '点击', time: '耗时', perf: '性能' }[b.kind]} | ${b.budget === null ? '只记录' : b.budget + b.unit} | ${b.actual ?? '—'}${b.actual === null ? '' : b.unit} | ${b.budget === null ? (b.actual === null ? '未测' : '—') : ok(b) ? '达标' : '超标'} |`);
  }
  o.push('', '## 无障碍', '', i.a11y ? `扫描 ${i.a11y.pages} 个页面状态：critical ${i.a11y.critical}，serious ${i.a11y.serious}` : '未扫描。');
  o.push('', '## 可用性测试', '');
  if (!i.usability?.tasks.length) o.push('未进行。');
  else {
    o.push('| 角色 | 任务 | 人数 | 完成 | 完成率 | 中位耗时 |', '|---|---|---|---|---|---|');
    for (const t of i.usability.tasks) o.push(`| ${t.role} | ${t.task} | ${t.participants} | ${t.completed} | ${pct(t.completed, t.participants)}% | ${t.medianSec ?? '—'}s |`);
  }
  o.push('', '## 延期项', '');
  if (!i.deferred.length) o.push('无。');
  else {
    o.push('| 项目 | 理由 | 跟进计划 |', '|---|---|---|');
    for (const d of i.deferred) o.push(`| ${esc(d.item)} | ${esc(d.reason)} | ${esc(d.plan ?? '')} |`);
  }
  const failed = [...r.latest.values()].filter((x) => x.result !== 'PASSED');
  if (failed.length) {
    o.push('', '## 未通过的用例', '', '| 用例 | 结果 | 来源 | 说明 | 证据 |', '|---|---|---|---|---|');
    for (const x of failed) o.push(`| ${x.caseId} | ${x.result} | ${x.source} | ${esc((x.error ?? x.notes ?? '').slice(0, 200))} | ${x.evidence.slice(0, 3).map((e) => `[${path.basename(e)}](${e})`).join(' ')} |`);
  }
  return o.join('\n') + '\n';
}

/** 极简 Markdown → HTML（只处理本报告用到的标题、列表、表格、引用、链接、行内代码） */
export function html(md: string, title: string, passed: boolean): string {
  const inline = (s: string) =>
    s
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/&lt;br&gt;/g, '<br>')
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2">$1</a>')
      .replace(/\\\|/g, '|');
  const cells = (l: string) => l.slice(1, -1).split(/(?<!\\)\|/).map((c) => c.trim());
  const out: string[] = [];
  const lines = md.split('\n');
  for (let k = 0; k < lines.length; k++) {
    const l = lines[k];
    if (l.startsWith('|') && lines[k + 1]?.startsWith('|---')) {
      out.push('<table><thead><tr>' + cells(l).map((c) => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>');
      k++;
      while (lines[k + 1]?.startsWith('|')) out.push('<tr>' + cells(lines[++k]).map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>');
      out.push('</tbody></table>');
    } else if (/^#{1,3} /.test(l)) {
      const n = l.indexOf(' ');
      const text = l.slice(n + 1);
      const cls = text.startsWith('结论') ? ` class="verdict ${passed ? 'pass' : 'fail'}"` : '';
      out.push(`<h${n}${cls}>${inline(text)}</h${n}>`);
    } else if (l.startsWith('- ')) {
      out.push('<ul>');
      k--;
      while (lines[k + 1]?.startsWith('- ')) out.push(`<li>${inline(lines[++k].slice(2))}</li>`);
      out.push('</ul>');
    } else if (l.startsWith('> ')) out.push(`<blockquote>${inline(l.slice(2))}</blockquote>`);
    else if (l.trim()) out.push(`<p>${inline(l)}</p>`);
  }
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${inline(title)}</title>
<style>
:root{--bg:#fff;--fg:#1f2329;--muted:#646a73;--line:#dee0e3;--head:#f5f6f7;--pass:#1f7a3d;--pass-bg:#e8f6ed;--fail:#b42318;--fail-bg:#fdecea;--link:#2456c8}
@media (prefers-color-scheme:dark){:root{--bg:#17181a;--fg:#e8e9eb;--muted:#a3a7ad;--line:#33363b;--head:#212327;--pass:#6ad08d;--pass-bg:#173523;--fail:#ff8a7a;--fail-bg:#3d1a17;--link:#8fb0ff}}
body{margin:0;padding:24px 16px;background:var(--bg);color:var(--fg);font:14px/1.6 system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
main{max-width:1100px;margin:0 auto}h1{font-size:22px}h2{font-size:18px;margin-top:28px;border-bottom:1px solid var(--line);padding-bottom:4px}h3{font-size:15px}
.verdict{padding:8px 12px;border-radius:6px;border:0}.verdict.pass{color:var(--pass);background:var(--pass-bg)}.verdict.fail{color:var(--fail);background:var(--fail-bg)}
table{border-collapse:collapse;width:100%;margin:8px 0;display:block;overflow-x:auto}th,td{border:1px solid var(--line);padding:4px 8px;text-align:left;vertical-align:top}th{background:var(--head)}
code{font-family:ui-monospace,monospace;background:var(--head);padding:0 4px;border-radius:3px}a{color:var(--link)}blockquote{margin:8px 0;padding:6px 12px;border-left:3px solid var(--fail);color:var(--muted)}
</style></head><body><main>
${out.join('\n')}
</main></body></html>
`;
}

const readJson = <T>(f: string): T | null => (fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null);

export function loadInputs(dir: string, commit?: string): Inputs {
  const run = path.basename(dir);
  const dfile = path.join(ACC, 'deferred.yaml');
  return {
    run,
    commit: commit ?? (/-([0-9a-f]{7,40})$/.exec(run)?.[1] || execFileSync('git', ['rev-parse', '--short', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim()),
    changes: config().changes,
    cases: loadCases(),
    scenarios: loadScenarios(),
    results: readJson<{ rows: ResultRow[] }>(path.join(dir, 'results.json'))?.rows ?? [],
    defects: readJson<DefectSync>(path.join(dir, 'defects.json')),
    budgets: readJson<{ items: Budget[] }>(path.join(dir, 'budgets.json'))?.items ?? null,
    a11y: readJson<A11y>(path.join(dir, 'a11y.json')),
    usability: readJson<Usability>(path.join(dir, 'usability.json')),
    deferred: fs.existsSync(dfile) ? (YAML.parse(fs.readFileSync(dfile, 'utf8'))?.items ?? []) : []
  };
}

if (import.meta.main) {
  const [run, ...rest] = process.argv.slice(2);
  if (!run) {
    console.error('用法：node tools/report.ts <run> [--commit <sha>]');
    process.exit(2);
  }
  const dir = runDirOf(run);
  const commit = rest.includes('--commit') ? rest[rest.indexOf('--commit') + 1] : undefined;
  const r = build(loadInputs(dir, commit));
  const md = markdown(r);
  fs.writeFileSync(path.join(dir, 'report.md'), md);
  fs.writeFileSync(path.join(dir, 'report.html'), html(md, `验收报告 · ${r.run}`, r.passed));
  console.log(`结论：${r.passed ? '通过' : '不通过'}（${path.relative(process.cwd(), path.join(dir, 'report.md'))}、report.html）`);
  for (const x of r.reasons) console.log(`  - ${x}`);
  process.exit(r.passed ? 0 : 1);
}
