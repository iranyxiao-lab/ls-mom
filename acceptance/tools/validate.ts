/**
 * 用例校验：按 schema/case.schema.json 校验每条用例，另查编号唯一、AUTO 用例的脚本文件存在且包含 #后的测试标题片段。
 * 运行：node tools/validate.ts [用例目录]；有问题时逐条列出用例编号并以非 0 退出。
 */
import fs from 'node:fs';
import path from 'node:path';
import AjvModule from 'ajv';
import { ACC, ROOT, loadCases, type Case } from './lib.ts';

const Ajv = (AjvModule as any).default ?? AjvModule;
const schema = JSON.parse(fs.readFileSync(path.join(ACC, 'schema/case.schema.json'), 'utf8'));
const check = new Ajv({ allErrors: true, strict: false }).compile(schema);

export interface Problem {
  id: string;
  file?: string;
  message: string;
}

const describe = (e: any) => {
  const at = e.instancePath ? e.instancePath.replace(/^\//, '').replace(/\//g, '.') : '';
  if (e.keyword === 'required') return `缺少 ${at ? at + '.' : ''}${e.params.missingProperty}`;
  if (e.keyword === 'additionalProperties') return `不认识的字段 ${at ? at + '.' : ''}${e.params.additionalProperty}`;
  if (e.keyword === 'enum') return `${at} 只能是 ${e.params.allowedValues.join(' / ')}`;
  if (e.keyword === 'if') return '';
  return `${at} ${e.message}`;
};

export function validateCases(cases: Case[]): Problem[] {
  const problems: Problem[] = [];
  const seen = new Map<string, string>();
  for (const c of cases) {
    const id = c?.id ?? '（无编号）';
    const { file, ...body } = c as any;
    if (!check(body)) {
      for (const e of check.errors ?? []) {
        const m = describe(e);
        if (m) problems.push({ id, file, message: m });
      }
    }
    if (typeof c.id === 'string') {
      if (seen.has(c.id)) problems.push({ id, file, message: `编号重复（另见 ${seen.get(c.id)}）` });
      else seen.set(c.id, file ?? '');
    }
    if (c.mode === 'AUTO' && typeof c.script === 'string') {
      const [rel, fragment] = c.script.split('#');
      const p = path.join(ROOT, rel);
      if (!fs.existsSync(p)) problems.push({ id, file, message: `自动化脚本不存在：${rel}` });
      else if (fragment && !fs.readFileSync(p, 'utf8').includes(fragment)) {
        problems.push({ id, file, message: `脚本 ${rel} 中找不到测试片段「${fragment}」` });
      }
    }
  }
  return problems;
}

if (import.meta.main) {
  const dir = process.argv[2] ? path.resolve(process.argv[2]) : path.join(ACC, 'cases');
  const cases = loadCases(dir);
  const problems = validateCases(cases);
  if (problems.length) {
    console.error(`用例校验失败：${problems.length} 个问题`);
    for (const p of problems) console.error(`  ${p.id}（${p.file}）：${p.message}`);
    process.exit(1);
  }
  console.log(`用例校验通过：${cases.length} 条`);
}
