/**
 * 按"场景 → 验证脚本"映射批量生成用例（acceptance-walkthrough 第 4～6 组；后续新 change 补用例同样适用）。
 * 用例的步骤与预期取自 Scenario 的 WHEN/GIVEN（步骤）与 THEN/AND（预期），编号按前缀顺排；
 * 已有用例文件中的用例保留不动（按场景去重），只追加尚未覆盖的场景。
 * 运行：node tools/scaffold.ts <映射.yaml> [...]
 * 映射格式：
 *   change: bootstrap-ls-mom-mvp
 *   spec: core/party-org
 *   prefix: AC-CORE-PO            # 编号前缀
 *   roles: [系统管理员]            # 默认角色
 *   slice: PLATFORM               # 可选
 *   dataset: acceptance           # 可选，默认 acceptance
 *   cases:
 *     用户创建时生成 Party: ls-mom-modules/…/PartyOrgTest.java#用户创建时生成Party
 *     外部协作方无登录: { script: …, roles: [调度员], codes: [DOM-01] }
 *     首次推送: { manual: 需 GitHub 仓库，人工核对 }   # 人工用例，值为说明
 *   场景名在 spec 内重复时写成 "Requirement ▸ Scenario"。
 */
import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';
import { ACC, loadScenarios, scenariosOf, type Case, type Scenario } from './lib.ts';

export interface Mapping {
  change: string;
  spec: string;
  prefix: string;
  roles: string[];
  slice?: string;
  dataset?: string;
  cases: Record<string, string | { script?: string; manual?: string; roles?: string[]; codes?: string[]; title?: string; journey?: string; priority?: string }>;
}

/** Scenario 正文 → 步骤与预期 */
export function stepsOf(text: string): { steps: string[]; expected: string[] } {
  const steps: string[] = [];
  const expected: string[] = [];
  let into = steps;
  for (const raw of text.split('\n')) {
    const m = /^\s*-\s*\*\*(GIVEN|WHEN|THEN|AND)\*\*\s*(.+)$/.exec(raw);
    if (!m) continue;
    if (m[1] === 'THEN') into = expected;
    else if (m[1] === 'WHEN' || m[1] === 'GIVEN') into = steps;
    into.push(m[2].trim());
  }
  return { steps, expected };
}

export function scaffold(map: Mapping, scenarios: Scenario[], existing: Case[]): { cases: Case[]; added: Case[]; missing: string[]; unknown: string[] } {
  const mine = scenarios.filter((s) => s.change === map.change && s.spec === map.spec);
  const find = (k: string) => {
    const [a, b] = k.includes('▸') ? k.split('▸').map((x) => x.trim()) : [null, k.trim()];
    const hits = mine.filter((s) => s.scenario === b && (!a || s.requirement === a));
    if (hits.length > 1) throw new Error(`${map.spec}：场景"${k}"不唯一，请写成"Requirement ▸ Scenario"`);
    return hits[0];
  };
  const covered = new Set(existing.flatMap((c) => scenariosOf(c).map((s) => `${c.ref.requirement}▸${s}`)));
  const used = new Set(existing.map((c) => c.id));
  let n = 0;
  const nextId = () => {
    let id: string;
    do id = `${map.prefix}-${String(++n).padStart(3, '0')}`;
    while (used.has(id));
    used.add(id);
    return id;
  };
  const added: Case[] = [];
  const unknown: string[] = [];
  for (const [k, v] of Object.entries(map.cases)) {
    const s = find(k);
    if (!s) {
      unknown.push(k);
      continue;
    }
    if (covered.has(`${s.requirement}▸${s.scenario}`)) continue;
    const o = typeof v === 'string' ? { script: v } : v;
    const { steps, expected } = stepsOf(s.text);
    const c: any = {
      id: nextId(),
      title: o.title ?? s.scenario,
      ref: { change: map.change, spec: map.spec, requirement: s.requirement, scenario: s.scenario },
      ...(o.codes ? { codes: o.codes } : {}),
      ...(map.slice ? { slice: map.slice } : {}),
      roles: o.roles ?? map.roles,
      dataset: map.dataset ?? 'acceptance',
      steps: steps.length ? steps : [s.scenario],
      expected: expected.length ? expected : ['符合 spec 描述'],
      mode: o.manual !== undefined ? 'MANUAL' : 'AUTO',
      ...(o.script ? { script: o.script } : {}),
      ...(o.journey ? { journey: o.journey } : {}),
      ...(o.priority ? { priority: o.priority } : {}),
      ...(o.manual ? { notes: o.manual } : {})
    };
    added.push(c);
    covered.add(`${s.requirement}▸${s.scenario}`);
  }
  const missing = mine.filter((s) => !covered.has(`${s.requirement}▸${s.scenario}`)).map((s) => `${s.requirement} ▸ ${s.scenario}`);
  return { cases: [...existing, ...added], added, missing, unknown };
}

const yamlOf = (cases: Case[]) => {
  const doc = new YAML.Document({ cases: cases.map(({ file, ...c }: any) => c) }, { aliasDuplicateObjects: false });
  // ref 用行内写法，便于一眼看出关联
  YAML.visit(doc, {
    Pair(_, p: any) {
      if (p.key?.value === 'ref' || p.key?.value === 'codes' || p.key?.value === 'roles' || p.key?.value === 'covers') p.value.flow = true;
    }
  });
  return doc.toString({ lineWidth: 0 });
};

if (import.meta.main) {
  const files = process.argv.slice(2);
  if (!files.length) {
    console.error('用法：node tools/scaffold.ts <映射.yaml> [...]');
    process.exit(2);
  }
  const scenarios = loadScenarios();
  let bad = false;
  for (const f of files) {
    const map = YAML.parse(fs.readFileSync(f, 'utf8')) as Mapping;
    const out = path.join(ACC, 'cases', map.change, map.spec, 'cases.yaml');
    const existing: Case[] = fs.existsSync(out) ? (YAML.parse(fs.readFileSync(out, 'utf8'))?.cases ?? []) : [];
    const r = scaffold(map, scenarios, existing);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, yamlOf(r.cases));
    console.log(`${map.spec}：新增 ${r.added.length} 条，共 ${r.cases.length} 条${r.missing.length ? `，仍未覆盖 ${r.missing.length}：${r.missing.join('；')}` : ''}`);
    if (r.unknown.length) {
      bad = true;
      console.error(`  映射中的场景不存在：${r.unknown.join('；')}`);
    }
  }
  process.exit(bad ? 1 : 0);
}
