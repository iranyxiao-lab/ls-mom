/**
 * 缺陷同步与回归（acceptance-walkthrough 3.1 / 3.3），缺陷记在 GitHub Issues（模板 .github/ISSUE_TEMPLATE/defect.yml）。
 *   node tools/defects.ts labels                     创建 defect、S1～S4、type/* 标签（已存在则更新）
 *   node tools/defects.ts label <issue>              按 Issue 表单内容补打 S*、type/*、spec/* 标签；用例编号不在用例库中时评论提示
 *   node tools/defects.ts sync <run>                 拉取全部缺陷写 runs/<run>/defects.json；网络或 gh 失败时标注"未同步"（不影响走查）
 *   node tools/defects.ts regress <run> --issues 1,2 | --pr-body <file>
 *        重跑缺陷关联的用例与同一旅程（run-auto），结果评论回 Issue；回归失败则重新打开并附失败证据
 * 依赖已登录的 gh（CI 中用 GH_TOKEN）。
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { key, loadCases, scenariosOf, type Case } from './lib.ts';
import { runDirOf, type ResultRow } from './collect.ts';

export type Severity = 'S1' | 'S2' | 'S3' | 'S4';
export interface Defect {
  number: number;
  title: string;
  state: 'OPEN' | 'CLOSED';
  url: string;
  severity: Severity | null;
  type: string | null;
  caseIds: string[];
  specs: string[];
  scenario: string | null;
  /** S3 的处理决定：fix / defer（标签 decision/fix、decision/defer） */
  decision: 'fix' | 'defer' | null;
  labels: string[];
}
export interface DefectSync {
  synced: boolean;
  fetchedAt: string;
  error?: string;
  defects: Defect[];
}

export const SEVERITIES: Record<Severity, string> = { S1: '阻断', S2: '严重', S3: '一般', S4: '轻微' };
export const TYPES: Record<string, string> = { functional: '功能', ux: 'UI 体验', a11y: '无障碍', performance: '性能', security: '安全' };
const LABELS: [string, string, string][] = [
  ['defect', 'd73a4a', '验收走查发现的缺陷'],
  ['S1', 'b60205', '阻断：核心流程走不通、数据错误、越权、安全问题'],
  ['S2', 'd93f0b', '严重：结果不对或关键任务超出操作预算'],
  ['S3', 'fbca04', '一般：体验或一致性问题，有替代路径'],
  ['S4', 'c5def5', '轻微：文案、样式细节'],
  ...Object.entries(TYPES).map(([k, v]) => [`type/${k}`, '5319e7', v] as [string, string, string]),
  ['decision/fix', '0e8a16', 'S3 处理决定：修复'],
  ['decision/defer', 'bfdadc', 'S3 处理决定：带说明延期'],
  ['regression/failed', 'b60205', '回归未通过']
];

export type Gh = (args: string[], input?: string) => string;
const realGh: Gh = (args, input) => execFileSync('gh', args, { encoding: 'utf8', input, stdio: ['pipe', 'pipe', 'pipe'], timeout: 60_000 });

/** Issue 表单正文（"### 字段\n\n值"）→ 字段表 */
export function fields(body: string): Record<string, string> {
  const out: Record<string, string> = {};
  const parts = (body ?? '').split(/^### /m).slice(1);
  for (const p of parts) {
    const [head, ...rest] = p.split('\n');
    const v = rest.join('\n').trim();
    out[head.trim()] = v === '_No response_' ? '' : v;
  }
  return out;
}

const ID = /AC-[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\d{3}/g;

export function parseDefect(i: { number: number; title: string; state: string; url: string; body: string; labels: { name: string }[] }): Defect {
  const f = fields(i.body);
  const labels = i.labels.map((l) => l.name);
  const sevField = /\b(S[1-4])\b/.exec(f['严重级别'] ?? '')?.[1] as Severity | undefined;
  const sevLabel = labels.find((l) => /^S[1-4]$/.test(l)) as Severity | undefined;
  const typeField = Object.entries(TYPES).find(([, v]) => (f['类型'] ?? '').includes(v))?.[0];
  const typeLabel = labels.find((l) => l.startsWith('type/'))?.slice(5);
  const ref = f['关联 spec 与 Scenario'] ?? '';
  const specs = [...new Set([...ref.matchAll(/\b([a-z0-9-]+\/[a-z0-9-]+)\b/g)].map((m) => m[1]).concat(labels.filter((l) => l.startsWith('spec/')).map((l) => l.slice(5))))];
  const scenario = /Scenario[:：]\s*(.+)/.exec(ref)?.[1]?.trim() ?? (ref.split('▸').length >= 3 ? ref.split('▸').pop()!.trim() : null);
  return {
    number: i.number,
    title: i.title,
    state: i.state === 'CLOSED' ? 'CLOSED' : 'OPEN',
    url: i.url,
    // 标签优先（复核后改级以标签为准）
    severity: sevLabel ?? sevField ?? null,
    type: typeLabel ?? typeField ?? null,
    caseIds: [...new Set((f['关联用例编号'] ?? '').match(ID) ?? [])],
    specs,
    scenario,
    decision: labels.includes('decision/fix') ? 'fix' : labels.includes('decision/defer') ? 'defer' : null,
    labels
  };
}

export function labelsFor(d: Defect): string[] {
  return ['defect', ...(d.severity ? [d.severity] : []), ...(d.type ? [`type/${d.type}`] : []), ...d.specs.map((s) => `spec/${s}`)];
}

export function fetchDefects(gh: Gh = realGh): DefectSync {
  const fetchedAt = new Date().toISOString();
  try {
    const raw = gh(['issue', 'list', '--label', 'defect', '--state', 'all', '--limit', '1000', '--json', 'number,title,state,url,body,labels']);
    return { synced: true, fetchedAt, defects: (JSON.parse(raw) as any[]).map(parseDefect).sort((a, b) => a.number - b.number) };
  } catch (e: any) {
    return { synced: false, fetchedAt, error: String(e?.stderr || e?.message || e).split('\n')[0], defects: [] };
  }
}

/** 场景键 → 未关闭缺陷（缺陷经用例编号或 spec＋Scenario 关联到场景） */
export function openDefectsByScenario(defects: Defect[], cases: Case[]): Map<string, Defect[]> {
  const byCase = new Map(cases.map((c) => [c.id, c]));
  const out = new Map<string, Defect[]>();
  const add = (k: string, d: Defect) => {
    const l = out.get(k) ?? [];
    if (!l.includes(d)) out.set(k, [...l, d]);
  };
  for (const d of defects.filter((x) => x.state === 'OPEN')) {
    for (const id of d.caseIds) {
      const c = byCase.get(id);
      if (c) for (const s of scenariosOf(c)) add(key(c.ref.spec, c.ref.requirement, s), d);
    }
    if (d.scenario)
      for (const c of cases.filter((x) => d.specs.includes(x.ref.spec) && scenariosOf(x).includes(d.scenario!))) add(key(c.ref.spec, c.ref.requirement, d.scenario), d);
  }
  return out;
}

/** 回归范围：缺陷关联的用例＋同一旅程（journey 字段相同或 journeys/ 下引用了这些用例的旅程用例） */
export function regressionCases(d: Defect, cases: Case[]): Case[] {
  const direct = cases.filter((c) => d.caseIds.includes(c.id));
  const journeys = new Set(direct.map((c) => c.journey).filter(Boolean));
  const sameJourney = cases.filter((c) => journeys.has(c.journey) || (c.covers ?? []).some((id: string) => d.caseIds.includes(id)));
  return [...new Set([...direct, ...sameJourney])];
}

export const fixesOf = (body: string) => [...new Set([...(body ?? '').matchAll(/\b(?:fix(?:e[sd])?|close[sd]?|resolve[sd]?)\s+#(\d+)/gi)].map((m) => Number(m[1])))];

export function regressionComment(d: Defect, rows: ResultRow[], run: string, link?: string): { ok: boolean; body: string } {
  const ok = rows.length > 0 && rows.every((r) => r.result === 'PASSED');
  const lines = [
    `### ${ok ? '✅ 回归通过' : '❌ 回归未通过'}（${run}）`,
    '',
    rows.length ? '| 用例 | 结果 | 耗时 | 说明 |\n|---|---|---|---|' : `没有可执行的关联用例（关联用例编号：${d.caseIds.join('、') || '无'}），请补充用例编号或用例脚本。`,
    ...rows.map((r) => `| ${r.caseId} | ${r.result} | ${(r.durationMs / 1000).toFixed(1)}s | ${(r.error ?? '').replace(/\|/g, '\\|').slice(0, 300)} |`),
    '',
    ...(link ? [`证据：${link}`] : []),
    ok ? '' : '已重新打开；修复后请再次提交 `Fixes #' + d.number + '`。'
  ];
  return { ok, body: lines.join('\n').trim() + '\n' };
}

if (import.meta.main) {
  const [cmd, arg, ...rest] = process.argv.slice(2);
  const opt = (n: string) => (rest.includes(n) ? rest[rest.indexOf(n) + 1] : undefined);
  const gh = realGh;
  if (cmd === 'labels') {
    for (const [name, color, desc] of LABELS) gh(['label', 'create', name, '--color', color, '--description', desc, '--force']);
    console.log(`已创建或更新 ${LABELS.length} 个标签`);
  } else if (cmd === 'label') {
    const i = JSON.parse(gh(['issue', 'view', arg, '--json', 'number,title,state,url,body,labels']));
    const d = parseDefect(i);
    const want = labelsFor(d).filter((l) => !d.labels.includes(l));
    for (const l of want.filter((x) => x.startsWith('spec/'))) gh(['label', 'create', l, '--color', '1d76db', '--force']);
    if (want.length) gh(['issue', 'edit', arg, ...want.flatMap((l) => ['--add-label', l])]);
    const known = new Set(loadCases().map((c) => c.id));
    const unknown = d.caseIds.filter((x) => !known.has(x));
    if (!d.caseIds.length || unknown.length)
      gh(['issue', 'comment', arg, '--body', `⚠️ 关联用例编号${d.caseIds.length ? ` ${unknown.join('、')} 不在用例库中` : '格式不正确（应形如 AC-FLOW-WC-012）'}，请编辑 Issue 修正，否则无法回归与关联报告。`]);
    console.log(`#${arg}：${d.severity ?? '未定级'} ${d.type ?? ''}，补打标签 ${want.join(', ') || '无'}`);
  } else if (cmd === 'sync') {
    const r = fetchDefects(gh);
    const dir = runDirOf(arg);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'defects.json'), JSON.stringify(r, null, 2));
    const open = r.defects.filter((d) => d.state === 'OPEN');
    console.log(r.synced ? `已同步 ${r.defects.length} 个缺陷（未关闭 ${open.length}：${(['S1', 'S2', 'S3', 'S4'] as Severity[]).map((s) => `${s} ${open.filter((d) => d.severity === s).length}`).join('，')}）` : `缺陷数据未同步：${r.error}`);
  } else if (cmd === 'regress') {
    const { runAuto } = await import('./run-auto.ts');
    const dir = runDirOf(arg);
    fs.mkdirSync(dir, { recursive: true });
    const body = opt('--pr-body') ? fs.readFileSync(opt('--pr-body')!, 'utf8') : '';
    const nums = opt('--issues') ? opt('--issues')!.split(',').map(Number) : fixesOf(body);
    const cases = loadCases();
    let failed = false;
    for (const n of nums) {
      const d = parseDefect(JSON.parse(gh(['issue', 'view', String(n), '--json', 'number,title,state,url,body,labels'])));
      if (!d.labels.includes('defect')) continue;
      const rows = runAuto(regressionCases(d, cases), dir);
      const c = regressionComment(d, rows, path.basename(dir), process.env.ACC_EVIDENCE_URL);
      gh(['issue', 'comment', String(n), '--body-file', '-'], c.body);
      if (!c.ok) {
        failed = true;
        if (d.state === 'CLOSED') gh(['issue', 'reopen', String(n)]);
        gh(['label', 'create', 'regression/failed', '--color', 'b60205', '--force']);
        gh(['issue', 'edit', String(n), '--add-label', 'regression/failed']);
      } else if (d.labels.includes('regression/failed')) gh(['issue', 'edit', String(n), '--remove-label', 'regression/failed']);
      console.log(`#${n}：${c.ok ? '回归通过' : '回归未通过'}（${rows.length} 条用例）`);
    }
    if (!nums.length) console.log('没有关联的缺陷（PR 正文中没有 Fixes #n）');
    process.exit(failed ? 1 : 0);
  } else {
    console.error('用法：node tools/defects.ts labels | label <issue> | sync <run> | regress <run> --issues 1,2 | --pr-body <file>');
    process.exit(2);
  }
}
