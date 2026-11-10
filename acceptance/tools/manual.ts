/**
 * 人工用例与 UI 走查检查表（acceptance-walkthrough 2.5）。
 *   生成：node tools/manual.ts generate <run> [--scope all|cases|ui] [--pages 页面编号,...] [--viewports phone,tablet,desktop] [--themes light,dark]
 *         → runs/<run>/manual-checklist.md（人工用例逐条一节；UI 走查按 页面 × 视口 × 主题 一节，逐项列启发式检查清单）
 *   导入：node tools/manual.ts import <run> → 解析填写后的检查表，写 runs/<run>/manual.json（collect.ts 合并进 results.json）
 * 填写方式：用例结果在"结果"行把对应方框改成 [x]；UI 检查项在表格"结果"列填 通过 / 问题 / 不适用，问题须写说明，可附截图路径。
 */
import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';
import { ACC, loadCases, type Case } from './lib.ts';
import { runDirOf, type Outcome, type ResultRow } from './collect.ts';

export interface Page {
  id: string;
  path: string;
  name: string;
  area: string;
  roles?: string[];
}
export interface Heuristic {
  id: string;
  group: string;
  name: string;
  judge: string;
  example?: string;
}
export interface UiCheck {
  page: string;
  path: string;
  viewport: string;
  theme: string;
  item: string;
  name: string;
  result: 'PASS' | 'ISSUE' | 'NA' | 'TODO';
  note: string;
}

export const VIEWPORTS: Record<string, string> = { phone: '手机 390×844', tablet: '平板 820×1180', desktop: '桌面 1440×900' };
export const THEMES: Record<string, string> = { light: '浅色', dark: '深色' };
const OUTCOMES: [Outcome, string][] = [
  ['PASSED', '通过'],
  ['FAILED', '失败'],
  ['BLOCKED', '阻塞'],
  ['SKIPPED', '跳过']
];

export function loadPages(file = path.join(ACC, 'pages.yaml')): Page[] {
  return fs.existsSync(file) ? (YAML.parse(fs.readFileSync(file, 'utf8')).pages ?? []) : [];
}
export function loadHeuristics(file = path.join(ACC, 'checklists/ui-heuristics.yaml')): Heuristic[] {
  if (!fs.existsSync(file)) return [];
  const doc = YAML.parse(fs.readFileSync(file, 'utf8'));
  return (doc.groups ?? []).flatMap((g: any) => (g.items ?? []).map((i: any) => ({ ...i, group: g.name })));
}

const cell = (s: string) => s.replace(/\|/g, '\\|').replace(/\n/g, ' ');

export function generate(opts: { cases?: Case[]; pages?: Page[]; heuristics?: Heuristic[]; viewports?: string[]; themes?: string[]; run: string }): string {
  const out: string[] = [`# 人工走查检查表 · ${opts.run}`, '', '填写说明：用例在"结果"行把一个方框改成 [x]；UI 检查项"结果"列填 通过 / 问题 / 不适用，问题必须写说明（可附截图路径）。', ''];
  const cases = opts.cases ?? [];
  if (cases.length) {
    out.push(`## 人工用例（${cases.length}）`, '');
    for (const c of cases) {
      out.push(`### ${c.id} ${c.title}`, '');
      out.push(`- 关联：${c.ref.spec} ▸ ${c.ref.requirement} ▸ ${[c.ref.scenario].flat().join(' / ')}`);
      out.push(`- 角色：${c.roles.join('、')}；数据集：${c.dataset}`);
      out.push('- 步骤：');
      c.steps.forEach((s, i) => out.push(`  ${i + 1}. ${s}`));
      out.push('- 预期：');
      c.expected.forEach((s) => out.push(`  - ${s}`));
      out.push(`- 结果：${OUTCOMES.map(([, l]) => `[ ] ${l}`).join(' ')}`);
      out.push('- 走查员：');
      out.push('- 说明：');
      out.push('');
    }
  }
  const pages = opts.pages ?? [];
  const items = opts.heuristics ?? [];
  if (pages.length && items.length) {
    const vps = opts.viewports ?? Object.keys(VIEWPORTS);
    const ths = opts.themes ?? Object.keys(THEMES);
    out.push(`## UI 走查（${pages.length} 个页面 × ${vps.length} 个视口 × ${ths.length} 个主题，每节 ${items.length} 项）`, '');
    for (const p of pages)
      for (const v of vps)
        for (const t of ths) {
          out.push(`### UI · ${p.id} · ${v} · ${t}`, '', `${p.name}（\`${p.path}\`）· ${VIEWPORTS[v] ?? v} · ${THEMES[t] ?? t}`, '');
          out.push('| 编号 | 检查项 | 判定说明 | 结果 | 说明 |', '|---|---|---|---|---|');
          for (const i of items) out.push(`| ${i.id} | ${cell(i.group + '：' + i.name)} | ${cell(i.judge)} |  |  |`);
          out.push('');
        }
  }
  return out.join('\n') + '\n';
}

export function parse(md: string): { rows: ResultRow[]; uiChecks: UiCheck[]; problems: string[] } {
  const rows: ResultRow[] = [];
  const uiChecks: UiCheck[] = [];
  const problems: string[] = [];
  let caseId: string | null = null;
  let title = '';
  let picked: Outcome[] = [];
  let tester = '';
  let note = '';
  let ui: { page: string; viewport: string; theme: string; path: string } | null = null;
  const flush = () => {
    if (!caseId) return;
    if (picked.length === 1) rows.push({ caseId, title, source: 'manual', result: picked[0], durationMs: 0, evidence: [], tester: tester || undefined, notes: note || undefined });
    else if (picked.length > 1) problems.push(`${caseId}：结果勾选了多项`);
    caseId = null;
  };
  for (const raw of md.split('\n')) {
    const line = raw.trimEnd();
    const h = /^### (AC-[A-Z0-9-]+-\d{3}) (.*)$/.exec(line);
    const u = /^### UI · (\S+) · (\S+) · (\S+)$/.exec(line);
    if (h || u || /^## /.test(line)) flush();
    if (h) {
      caseId = h[1];
      title = h[2];
      picked = [];
      tester = '';
      note = '';
      ui = null;
      continue;
    }
    if (u) {
      ui = { page: u[1], viewport: u[2], theme: u[3], path: '' };
      continue;
    }
    if (caseId) {
      if (line.startsWith('- 结果：')) picked = OUTCOMES.filter(([, l]) => new RegExp(`\\[[xX]\\]\\s*${l}`).test(line)).map(([o]) => o);
      else if (line.startsWith('- 走查员：')) tester = line.slice('- 走查员：'.length).trim();
      else if (line.startsWith('- 说明：')) note = line.slice('- 说明：'.length).trim();
      continue;
    }
    if (ui) {
      const p = /（`([^`]+)`）/.exec(line);
      if (p) ui.path = p[1];
      const r = /^\| ([A-Z][A-Z0-9-]*) \| (.+?) \| .+? \| (.*?) \| (.*?) \|$/.exec(line);
      if (r) {
        const v = r[3].trim();
        const result = v === '通过' ? 'PASS' : v === '问题' ? 'ISSUE' : v === '不适用' ? 'NA' : 'TODO';
        if (v && result === 'TODO') problems.push(`UI ${ui.page}/${ui.viewport}/${ui.theme} ${r[1]}：结果"${v}"无法识别`);
        if (result === 'ISSUE' && !r[4].trim()) problems.push(`UI ${ui.page}/${ui.viewport}/${ui.theme} ${r[1]}：问题须写说明`);
        uiChecks.push({ ...ui, item: r[1], name: r[2], result, note: r[4].trim() });
      }
    }
  }
  flush();
  return { rows, uiChecks, problems };
}

if (import.meta.main) {
  const [cmd, run, ...rest] = process.argv.slice(2);
  const opt = (n: string) => (rest.includes(n) ? rest[rest.indexOf(n) + 1] : undefined);
  if (!cmd || !run) {
    console.error('用法：node tools/manual.ts generate|import <run> [选项]');
    process.exit(2);
  }
  const dir = runDirOf(run);
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, 'manual-checklist.md');
  if (cmd === 'generate') {
    const scope = opt('--scope') ?? 'all';
    const only = opt('--pages')?.split(',');
    const md = generate({
      run: path.basename(dir),
      cases: scope === 'ui' ? [] : loadCases().filter((c) => c.mode === 'MANUAL'),
      pages: scope === 'cases' ? [] : loadPages().filter((p) => !only || only.includes(p.id)),
      heuristics: loadHeuristics(),
      viewports: opt('--viewports')?.split(','),
      themes: opt('--themes')?.split(',')
    });
    fs.writeFileSync(file, md);
    console.log(`已生成 ${path.relative(process.cwd(), file)}（${md.split('\n').length} 行）`);
  } else if (cmd === 'import') {
    const r = parse(fs.readFileSync(file, 'utf8'));
    fs.writeFileSync(path.join(dir, 'manual.json'), JSON.stringify({ rows: r.rows, uiChecks: r.uiChecks }, null, 2));
    const filled = r.uiChecks.filter((c) => c.result !== 'TODO').length;
    console.log(`已导入：人工用例 ${r.rows.length} 条，UI 检查项 ${filled}/${r.uiChecks.length} 项已填写，问题 ${r.uiChecks.filter((c) => c.result === 'ISSUE').length} 项`);
    if (r.problems.length) {
      console.error('检查表填写问题：\n  ' + r.problems.join('\n  '));
      process.exit(1);
    }
  }
}
