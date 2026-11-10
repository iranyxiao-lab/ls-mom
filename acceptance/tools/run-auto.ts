/**
 * 按用例脚本执行 AUTO 用例（acceptance-walkthrough 3.3 / 4.3 / 7.2），逐条结果写 runs/<run>/auto.json，由 collect.ts 合并。
 * 脚本类型（用例 script 字段：仓库相对路径，可带 #片段）：
 *   - *.java                    Maven Surefire：-Dtest=类#方法，按 surefire-reports 中同名测试判定（无 #片段 时整个类）
 *   - plus-ui/tests/e2e/*.mjs   端到端脚本：退出码为 0，且有 #片段 时输出中须有包含该片段的"✓ "检查行
 *   - plus-ui/tests/ui/**.spec.ts  Playwright（app / storybook 项目），-g 片段
 *   - plus-ui/** 其他 .test/.spec.ts  Vitest（无头），-t 片段
 *   - acceptance/tools/*.test.ts     node --test，--test-name-pattern 片段
 *   - acceptance/e2e/**.spec.ts      走查脚本：按 [AC-xxx] 标题 grep 执行（ACC_RUN=本轮），结果在 playwright.json，由 collect.ts 收集
 * 运行：node tools/run-auto.ts <run> [--ids AC-…,…] [--spec flow/x,…] [--kinds java,e2e,pw,vitest,node] [--dry]
 * 环境：LSMOM_E2E_BASE（端到端与 app 项目的被测地址，默认 http://127.0.0.1:18081）、MVN_ARGS（默认 -o -q）。
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { loadCases, ROOT, type Case } from './lib.ts';
import { fromPlaywright, runDirOf, type ResultRow } from './collect.ts';

export type Kind = 'java' | 'e2e' | 'pw' | 'vitest' | 'node' | 'walk';

export interface Target {
  kind: Kind;
  file: string;
  fragment?: string;
  case: Case;
}

export function targetOf(c: Case): Target | null {
  if (c.mode !== 'AUTO' || !c.script) return null;
  const [file, ...rest] = c.script.split('#');
  const fragment = rest.join('#') || undefined;
  const kind: Kind = file.endsWith('.java')
    ? 'java'
    : file.endsWith('.mjs')
      ? 'e2e'
      : file.startsWith('acceptance/e2e/')
        ? 'walk'
        : file.startsWith('acceptance/')
          ? 'node'
          : file.startsWith('plus-ui/tests/ui/')
            ? 'pw'
            : 'vitest';
  return { kind, file, fragment, case: c };
}

export interface Exec {
  (cmd: string, args: string[], opts: { cwd: string; env?: Record<string, string> }): { status: number | null; out: string };
}
const realExec: Exec = (cmd, args, opts) => {
  const r = spawnSync(cmd, args, { cwd: opts.cwd, env: { ...process.env, ...opts.env }, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  return { status: r.status, out: (r.stdout ?? '') + (r.stderr ?? '') + (r.error ? String(r.error) : '') };
};

const row = (t: Target, ok: boolean | null, ms: number, error?: string, evidence: string[] = []): ResultRow => ({
  caseId: t.case.id,
  title: t.case.title,
  source: 'auto',
  result: ok === null ? 'BLOCKED' : ok ? 'PASSED' : 'FAILED',
  durationMs: ms,
  startedAt: new Date(Date.now() - ms).toISOString(),
  evidence,
  error
});
const tail = (s: string, n = 40) => s.split('\n').slice(-n).join('\n');

/** Surefire XML → { 方法名: 失败信息|null } */
export function surefire(xml: string): Map<string, string | null> {
  const out = new Map<string, string | null>();
  for (const m of xml.matchAll(/<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g)) {
    const name = /\bname="([^"]*)"/.exec(m[1])?.[1]?.replace(/\(\)$/, '') ?? '';
    const body = m[3] ?? '';
    const f = /<(failure|error)\b([^>]*)/.exec(body);
    const msg = f ? (/\bmessage="([^"]*)"/.exec(f[2])?.[1] ?? f[1]) : null;
    const skipped = /<skipped\b/.test(body);
    out.set(name, msg ?? (skipped ? 'SKIPPED' : null));
  }
  return out;
}

function runJava(ts: Target[], exec: Exec, log: (s: string) => void): ResultRow[] {
  const rows: ResultRow[] = [];
  const byModule = new Map<string, Target[]>();
  for (const t of ts) {
    const mod = t.file.split('/src/test/java/')[0];
    byModule.set(mod, [...(byModule.get(mod) ?? []), t]);
  }
  for (const [mod, list] of byModule) {
    const classes = new Map<string, Set<string> | null>();
    for (const t of list) {
      const cls = path.basename(t.file, '.java');
      const prev = classes.get(cls);
      if (!t.fragment || prev === null) classes.set(cls, null);
      else classes.set(cls, new Set([...(prev ?? []), t.fragment]));
    }
    const filter = [...classes].map(([c, ms]) => (ms ? `${c}#${[...ms].join('+')}` : c)).join(',');
    const reports = path.join(ROOT, mod, 'target/surefire-reports');
    const fqcn = (t: Target) => t.file.split('/src/test/java/')[1].replace(/\.java$/, '').replaceAll('/', '.');
    for (const t of list) fs.rmSync(path.join(reports, `TEST-${fqcn(t)}.xml`), { force: true });
    log(`mvn ${mod} -Dtest=${filter}`);
    const t0 = Date.now();
    const r = exec('mvn', [...(process.env.MVN_ARGS ?? '-o -q').split(' ').filter(Boolean), '-pl', mod, 'test', `-Dtest=${filter}`, '-Dsurefire.failIfNoSpecifiedTests=false'], { cwd: ROOT });
    const ms = Date.now() - t0;
    for (const t of list) {
      const file = path.join(reports, `TEST-${fqcn(t)}.xml`);
      if (!fs.existsSync(file)) {
        rows.push(row(t, null, ms, `没有测试报告（mvn 退出码 ${r.status}）：${tail(r.out, 5).trim()}`));
        continue;
      }
      const res = surefire(fs.readFileSync(file, 'utf8'));
      const hits = [...res].filter(([n]) => !t.fragment || n === t.fragment || n.startsWith(t.fragment));
      if (!hits.length) rows.push(row(t, false, ms, `报告中没有测试 ${t.fragment}`, [path.relative(ROOT, file)]));
      else {
        const bad = hits.filter(([, e]) => e !== null);
        rows.push(row(t, bad.length === 0, ms, bad.map(([n, e]) => `${n}：${e}`).join('；') || undefined, [path.relative(ROOT, file)]));
      }
    }
  }
  return rows;
}

function runE2e(ts: Target[], exec: Exec, log: (s: string) => void, dir: string): ResultRow[] {
  const rows: ResultRow[] = [];
  const byFile = new Map<string, Target[]>();
  for (const t of ts) byFile.set(t.file, [...(byFile.get(t.file) ?? []), t]);
  const pause = Number(process.env.E2E_PAUSE_SECONDS ?? 0);
  let first = true;
  for (const [file, list] of byFile) {
    if (!first && pause) spawnSync('sleep', [String(pause)]);
    first = false;
    log(`node ${file}`);
    const t0 = Date.now();
    const r = exec('node', [path.join(ROOT, file)], { cwd: path.join(ROOT, 'plus-ui') });
    const ms = Date.now() - t0;
    const logFile = path.join(dir, 'auto-logs', path.basename(file) + '.log');
    fs.mkdirSync(path.dirname(logFile), { recursive: true });
    fs.writeFileSync(logFile, r.out);
    const ev = [path.relative(dir, logFile)];
    const checks = r.out.split('\n').filter((l) => l.startsWith('✓ '));
    for (const t of list) {
      if (r.status !== 0) rows.push(row(t, false, ms, (r.out.match(/断言失败：.*|Error: .*/) ?? [`退出码 ${r.status}`])[0], ev));
      else if (t.fragment && !checks.some((l) => l.includes(t.fragment!))) rows.push(row(t, false, ms, `输出中没有检查项"${t.fragment}"`, ev));
      else rows.push(row(t, true, ms, undefined, ev));
    }
  }
  return rows;
}

/** Playwright JSON 报告 → 每个用例按标题片段判定 */
function judgePw(ts: Target[], json: any, ms: number, err: string, ev: string[]): ResultRow[] {
  const tests: { title: string; ok: boolean; error?: string }[] = [];
  const visit = (s: any, prefix: string) => {
    for (const x of s.suites ?? []) visit(x, `${prefix}${x.title} › `);
    for (const sp of s.specs ?? [])
      for (const t of sp.tests ?? []) {
        const r = t.results?.[t.results.length - 1];
        tests.push({ title: `${prefix}${sp.title}`, ok: t.status === 'expected' || t.status === 'flaky', error: r?.error?.message?.split('\n')[0] });
      }
  };
  if (json) for (const s of json.suites ?? []) visit(s, '');
  return ts.map((t) => {
    const hit = tests.filter((x) => x.title.includes(path.basename(t.file)) && (!t.fragment || x.title.includes(t.fragment)));
    if (!json) return row(t, null, ms, err);
    if (!hit.length) return row(t, false, ms, `没有匹配"${t.fragment ?? t.file}"的测试`, ev);
    const bad = hit.filter((x) => !x.ok);
    return row(t, bad.length === 0, ms, bad.map((x) => `${x.title}：${x.error}`).join('；') || undefined, ev);
  });
}

function runPw(ts: Target[], exec: Exec, log: (s: string) => void, dir: string): ResultRow[] {
  if (!ts.length) return [];
  const ui = path.join(ROOT, 'plus-ui');
  const out = path.join(dir, 'auto-logs/plus-ui-playwright.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const files = [...new Set(ts.map((t) => path.relative('plus-ui', t.file)))];
  const projects = [...new Set(ts.map((t) => (t.file.includes('/storybook/') ? 'storybook' : t.file.includes('/visual/') ? 'visual' : 'app')))];
  log(`playwright ${files.join(' ')}`);
  const t0 = Date.now();
  const r = exec('pnpm', ['exec', 'playwright', 'test', ...files, ...projects.flatMap((p) => ['--project', p]), '--reporter=json'], {
    cwd: ui,
    env: { PLAYWRIGHT_JSON_OUTPUT_NAME: out, ...(projects.includes('storybook') ? {} : { LS_UI_NO_STORYBOOK: '1' }) }
  });
  const json = fs.existsSync(out) ? JSON.parse(fs.readFileSync(out, 'utf8')) : null;
  return judgePw(ts, json, Date.now() - t0, `Playwright 未产生报告（退出码 ${r.status}）：${tail(r.out, 5).trim()}`, [path.relative(dir, out)]);
}

function runVitest(ts: Target[], exec: Exec, log: (s: string) => void, dir: string): ResultRow[] {
  if (!ts.length) return [];
  const out = path.join(dir, 'auto-logs/plus-ui-vitest.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  const files = [...new Set(ts.map((t) => path.relative('plus-ui', t.file)))];
  log(`vitest ${files.join(' ')}`);
  const t0 = Date.now();
  const r = exec('pnpm', ['exec', 'vitest', 'run', '--browser.headless', ...files, '--reporter=json', `--outputFile=${out}`], { cwd: path.join(ROOT, 'plus-ui') });
  const ms = Date.now() - t0;
  if (!fs.existsSync(out)) return ts.map((t) => row(t, null, ms, `Vitest 未产生报告（退出码 ${r.status}）：${tail(r.out, 5).trim()}`));
  const doc = JSON.parse(fs.readFileSync(out, 'utf8'));
  const ev = [path.relative(dir, out)];
  return ts.map((t) => {
    const suite = (doc.testResults ?? []).find((x: any) => x.name.endsWith(path.relative('plus-ui', t.file)) || x.name.endsWith(t.file));
    const hit = (suite?.assertionResults ?? []).filter((a: any) => !t.fragment || a.fullName.includes(t.fragment) || a.title.includes(t.fragment));
    if (!hit.length) return row(t, false, ms, `没有匹配"${t.fragment ?? t.file}"的测试`, ev);
    const bad = hit.filter((a: any) => a.status !== 'passed');
    return row(t, bad.length === 0, ms, bad.map((a: any) => `${a.fullName}：${(a.failureMessages ?? [])[0]?.split('\n')[0] ?? a.status}`).join('；') || undefined, ev);
  });
}

function runNode(ts: Target[], exec: Exec, log: (s: string) => void): ResultRow[] {
  return ts.map((t) => {
    log(`node --test ${t.file}`);
    const t0 = Date.now();
    const args = ['--test', ...(t.fragment ? ['--test-name-pattern', t.fragment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')] : []), path.join(ROOT, t.file)];
    const r = exec('node', args, { cwd: path.join(ROOT, 'acceptance') });
    const passed = Number(/ℹ pass (\d+)/.exec(r.out)?.[1] ?? 0);
    const ok = r.status === 0 && passed > 0;
    return row(t, ok, Date.now() - t0, ok ? undefined : passed === 0 && r.status === 0 ? `没有匹配"${t.fragment}"的测试` : tail(r.out, 8).trim());
  });
}

function runWalk(ts: Target[], exec: Exec, log: (s: string) => void, dir: string): ResultRow[] {
  if (!ts.length) return [];
  const grep = ts.map((t) => `\\[${t.case.id}\\]`).join('|');
  log(`playwright（走查）--grep ${grep}`);
  const t0 = Date.now();
  const r = exec('npx', ['playwright', 'test', '--grep', grep], { cwd: path.join(ROOT, 'acceptance'), env: { ACC_RUN: path.basename(dir) } });
  const file = path.join(dir, 'playwright.json');
  if (!fs.existsSync(file)) return ts.map((t) => row(t, null, Date.now() - t0, `走查脚本未产生报告（退出码 ${r.status}）：${tail(r.out, 5).trim()}`));
  const got = fromPlaywright(file, dir);
  return ts.map((t) => got.find((x) => x.caseId === t.case.id) ?? row(t, false, Date.now() - t0, '没有标题含该编号的走查测试'));
}

export function runAuto(cases: Case[], dir: string, opts: { exec?: Exec; log?: (s: string) => void; kinds?: Kind[] } = {}): ResultRow[] {
  const exec = opts.exec ?? realExec;
  const log = opts.log ?? ((s: string) => console.log('▶ ' + s));
  const ts = cases.map(targetOf).filter((t): t is Target => !!t && (!opts.kinds || opts.kinds.includes(t.kind)));
  const of = (k: Kind) => ts.filter((t) => t.kind === k);
  return [...runJava(of('java'), exec, log), ...runE2e(of('e2e'), exec, log, dir), ...runPw(of('pw'), exec, log, dir), ...runVitest(of('vitest'), exec, log, dir), ...runNode(of('node'), exec, log), ...runWalk(of('walk'), exec, log, dir)];
}

/** 按编号、spec 前缀过滤用例 */
export function select(cases: Case[], f: { ids?: string[]; specs?: string[] }): Case[] {
  return cases.filter((c) => (!f.ids || f.ids.includes(c.id)) && (!f.specs || f.specs.some((s) => c.ref.spec === s || c.ref.spec.startsWith(s + '/') || c.ref.spec.startsWith(s))));
}

if (import.meta.main) {
  const [run, ...rest] = process.argv.slice(2);
  const opt = (n: string) => (rest.includes(n) ? rest[rest.indexOf(n) + 1]?.split(',').filter(Boolean) : undefined);
  if (!run) {
    console.error('用法：node tools/run-auto.ts <run> [--ids …] [--spec …] [--kinds …] [--dry]');
    process.exit(2);
  }
  const dir = runDirOf(run);
  fs.mkdirSync(dir, { recursive: true });
  const cases = select(loadCases(), { ids: opt('--ids'), specs: opt('--spec') });
  const kinds = opt('--kinds') as Kind[] | undefined;
  const ts = cases.map(targetOf).filter((t): t is Target => !!t);
  if (rest.includes('--dry')) {
    for (const t of ts) console.log(`${t.case.id}\t${t.kind}\t${t.file}${t.fragment ? '#' + t.fragment : ''}`);
    process.exit(0);
  }
  const rows = runAuto(cases, dir, { kinds });
  const file = path.join(dir, 'auto.json');
  // 增量执行（如缺陷回归只跑几条）时保留同一 run 中其他用例的结果
  const prev: ResultRow[] = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')).rows ?? [] : [];
  const ids = new Set(rows.map((r) => r.caseId));
  // 走查脚本的结果在 playwright.json 中，由 collect.ts 收集，不重复写入
  const own = rows.filter((r) => r.source === 'auto');
  fs.writeFileSync(file, JSON.stringify({ rows: [...prev.filter((r) => !ids.has(r.caseId)), ...own] }, null, 2));
  const by = (k: string) => rows.filter((r) => r.result === k).length;
  console.log(`已写入 ${path.relative(process.cwd(), file)}：${rows.length} 条（通过 ${by('PASSED')}，失败 ${by('FAILED')}，阻塞 ${by('BLOCKED')}）`);
  for (const r of rows.filter((x) => x.result !== 'PASSED')) console.log(`  ✗ ${r.caseId} ${r.result}：${r.error}`);
  process.exit(rows.some((r) => r.result !== 'PASSED') ? 1 : 0);
}
