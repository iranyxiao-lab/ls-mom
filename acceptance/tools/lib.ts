/** 验收工具共用：读取用例、优先级、被验收 change 的场景。 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import YAML from 'yaml';

export const ACC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const ROOT = path.resolve(ACC, '..');

export interface CaseRef {
  change: string;
  spec: string;
  requirement: string;
  scenario: string | string[];
}
export interface Case {
  id: string;
  title: string;
  ref: CaseRef;
  codes?: string[];
  slice?: string;
  roles: string[];
  dataset: string;
  steps: string[];
  expected: string[];
  mode: 'AUTO' | 'MANUAL';
  script?: string;
  priority?: 'P0' | 'P1' | 'P2';
  journey?: string;
  covers?: string[];
  notes?: string;
  /** 来源文件（相对 acceptance/） */
  file?: string;
}
export interface Scenario {
  change: string;
  spec: string;
  requirement: string;
  scenario: string;
  text: string;
}

export const key = (spec: string, requirement: string, scenario: string) => `${spec} ▸ ${requirement} ▸ ${scenario}`;
export const reqKey = (spec: string, requirement: string) => `${spec} ▸ ${requirement}`;
export const scenariosOf = (c: Case) => (Array.isArray(c.ref.scenario) ? c.ref.scenario : [c.ref.scenario]);

export function walk(dir: string, ext = /\.ya?ml$/): string[] {
  if (!fs.existsSync(dir)) return [];
  const out: string[] = [];
  for (const f of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, f.name);
    if (f.isDirectory()) out.push(...walk(p, ext));
    else if (ext.test(f.name)) out.push(p);
  }
  return out.sort();
}

/** 读取用例：每个 YAML 文件为 { cases: [...] } 或单条用例对象。 */
export function loadCases(dir = path.join(ACC, 'cases')): Case[] {
  const out: Case[] = [];
  for (const f of walk(dir)) {
    const doc = YAML.parse(fs.readFileSync(f, 'utf8'));
    const list = Array.isArray(doc?.cases) ? doc.cases : doc ? [doc] : [];
    for (const c of list) out.push({ ...c, file: path.relative(ACC, f) });
  }
  return out;
}

export function config(): { changes: string[] } {
  return YAML.parse(fs.readFileSync(path.join(ACC, 'config.yaml'), 'utf8'));
}

/** 优先级：priorities.yaml 中列出的 Requirement（spec ▸ requirement）及其验收编号，其余默认 P1。 */
export function priorities(): { p0: Map<string, string[]>; p2: Set<string> } {
  const file = path.join(ACC, 'priorities.yaml');
  const p0 = new Map<string, string[]>();
  const p2 = new Set<string>();
  if (!fs.existsSync(file)) return { p0, p2 };
  const doc = YAML.parse(fs.readFileSync(file, 'utf8')) ?? {};
  for (const item of doc.P0 ?? []) {
    const k = reqKey(item.spec, item.requirement);
    p0.set(k, [...new Set([...(p0.get(k) ?? []), ...(item.codes ?? [])])]);
  }
  for (const item of doc.P2 ?? []) p2.add(reqKey(item.spec, item.requirement));
  return { p0, p2 };
}

/** 被验收 change 的全部场景：优先用 openspec CLI（openspec show <change> --json --type change），没有 CLI 时直接解析 delta spec。 */
export function loadScenarios(changes = config().changes, source: 'auto' | 'cli' | 'markdown' = 'auto'): Scenario[] {
  const out: Scenario[] = [];
  for (const change of changes) {
    if (source !== 'markdown') {
      try {
        out.push(...fromCli(change));
        continue;
      } catch (e: any) {
        if (source === 'cli' || e.code !== 'ENOENT') throw e;
      }
    }
    out.push(...fromMarkdown(change));
  }
  return out;
}

function fromCli(change: string): Scenario[] {
  const raw = execFileSync('openspec', ['show', change, '--json', '--type', 'change'], { cwd: ROOT, encoding: 'utf8', maxBuffer: 64 << 20, stdio: ['ignore', 'pipe', 'pipe'] });
  const doc = JSON.parse(raw.slice(raw.indexOf('{')));
  const out: Scenario[] = [];
  for (const d of doc.deltas ?? []) {
    if (d.operation === 'REMOVED') continue;
    for (const s of d.requirement?.scenarios ?? []) out.push({ change, spec: d.spec, requirement: d.requirement.name, scenario: s.name, text: s.rawText ?? '' });
  }
  return out;
}

/** 解析 openspec/changes/<change>/specs/<spec>/spec.md 中 ADDED / MODIFIED 段落的 Requirement 与 Scenario。 */
export function fromMarkdown(change: string, base = path.join(ROOT, 'openspec/changes')): Scenario[] {
  const dir = path.join(base, change, 'specs');
  const out: Scenario[] = [];
  for (const file of walk(dir, /^spec\.md$/)) {
    const spec = path.relative(dir, path.dirname(file)).split(path.sep).join('/');
    let section = '';
    let req: string | null = null;
    let cur: Scenario | null = null;
    for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
      const h2 = /^## (ADDED|MODIFIED|REMOVED|RENAMED) Requirements/.exec(line);
      if (h2) {
        section = h2[1];
        req = null;
        cur = null;
        continue;
      }
      if (/^## /.test(line)) {
        section = '';
        req = null;
        cur = null;
        continue;
      }
      if (section !== 'ADDED' && section !== 'MODIFIED') continue;
      const r = /^### Requirement:\s*(.+?)\s*$/.exec(line);
      if (r) {
        req = r[1];
        cur = null;
        continue;
      }
      const s = /^#### Scenario:\s*(.+?)\s*$/.exec(line);
      if (s && req) {
        cur = { change, spec, requirement: req, scenario: s[1], text: '' };
        out.push(cur);
        continue;
      }
      if (cur && line.trim()) cur.text += (cur.text ? '\n' : '') + line.trim();
    }
  }
  return out;
}
