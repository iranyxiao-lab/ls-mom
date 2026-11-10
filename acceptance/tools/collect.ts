/**
 * 结果收集（acceptance-walkthrough 2.3）：把本轮各来源的执行结果合并成 runs/<run>/results.json，每条记录含用例编号、结果、耗时与证据路径。
 * 来源：
 *   - playwright.json：走查脚本（acceptance/e2e），用例编号取自测试标题中的 [AC-xxx]（可有多个）；
 *   - auto.json：tools/run-auto.ts 按用例脚本执行（Java 测试、Node 端到端脚本、前端测试）得到的逐条结果；
 *   - manual.json：tools/manual.ts 从填写好的人工检查表导入的结果。
 * 运行：node tools/collect.ts <run 目录名或路径>
 */
import fs from 'node:fs';
import path from 'node:path';
import { ACC } from './lib.ts';

export type Outcome = 'PASSED' | 'FAILED' | 'SKIPPED' | 'BLOCKED';

export interface ResultRow {
  caseId: string;
  title: string;
  source: 'playwright' | 'auto' | 'manual';
  result: Outcome;
  durationMs: number;
  startedAt?: string;
  evidence: string[];
  traceIds?: string[];
  error?: string;
  tester?: string;
  notes?: string;
}

export interface Results {
  run: string;
  generatedAt: string;
  rows: ResultRow[];
}

const ID = /\[(AC-[A-Z0-9-]+-\d{3})\]/g;
export const idsOf = (title: string) => [...title.matchAll(ID)].map((m) => m[1]);

const mapStatus = (s: string): Outcome => (s === 'passed' || s === 'expected' ? 'PASSED' : s === 'skipped' ? 'SKIPPED' : 'FAILED');

/** Playwright JSON 报告 → 结果行（一条测试对应标题里的每个用例编号）。 */
export function fromPlaywright(file: string, runDir: string): ResultRow[] {
  const doc = JSON.parse(fs.readFileSync(file, 'utf8'));
  const rows: ResultRow[] = [];
  const visit = (suite: any) => {
    for (const s of suite.suites ?? []) visit(s);
    for (const spec of suite.specs ?? []) {
      const ids = idsOf(spec.title);
      if (!ids.length) continue;
      for (const t of spec.tests ?? []) {
        const r = t.results?.[t.results.length - 1];
        if (!r) continue;
        const evidence = (r.attachments ?? [])
          .filter((a: any) => a.path)
          .map((a: any) => path.relative(runDir, a.path))
          .filter((p: string) => !p.startsWith('..'));
        const dirs = new Set(evidence.map((p: string) => p.split('/').slice(0, 2).join('/')));
        // 失败时 trace.zip、录像不一定作为附件列出：补上证据目录中的文件
        for (const d of dirs) {
          const abs = path.join(runDir, d);
          if (!fs.existsSync(abs)) continue;
          for (const f of ['trace.zip', 'traces.json', 'error-context.md']) if (fs.existsSync(path.join(abs, f))) evidence.push(path.join(d, f));
          const v = path.join(abs, 'video');
          if (fs.existsSync(v)) for (const f of fs.readdirSync(v)) evidence.push(path.join(d, 'video', f));
        }
        let traceIds: string[] | undefined;
        const tj = [...dirs].map((d) => path.join(runDir, d, 'traces.json')).find((p) => fs.existsSync(p));
        if (tj) traceIds = [...new Set(JSON.parse(fs.readFileSync(tj, 'utf8')).steps.flatMap((s: any) => s.traceIds))] as string[];
        const status = t.status === 'skipped' ? 'skipped' : r.status;
        for (const caseId of ids) {
          rows.push({
            caseId,
            title: spec.title,
            source: 'playwright',
            result: mapStatus(status),
            durationMs: r.duration ?? 0,
            startedAt: r.startTime,
            evidence: [...new Set(evidence)].sort(),
            traceIds,
            error: r.error?.message?.split('\n')[0]
          });
        }
      }
    }
  };
  for (const s of doc.suites ?? []) visit(s);
  return rows;
}

const readRows = (file: string): ResultRow[] => (fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')).rows ?? [] : []);

export function runDirOf(arg: string) {
  return path.isAbsolute(arg) ? arg : arg.includes('/') ? path.resolve(arg) : path.join(ACC, 'runs', arg);
}

export function collect(runDir: string): Results {
  const rows: ResultRow[] = [];
  const pw = path.join(runDir, 'playwright.json');
  if (fs.existsSync(pw)) rows.push(...fromPlaywright(pw, runDir));
  rows.push(...readRows(path.join(runDir, 'auto.json')));
  rows.push(...readRows(path.join(runDir, 'manual.json')));
  return { run: path.basename(runDir), generatedAt: new Date().toISOString(), rows };
}

if (import.meta.main) {
  const arg = process.argv[2];
  if (!arg) {
    console.error('用法：node tools/collect.ts <run 目录名或路径>');
    process.exit(2);
  }
  const dir = runDirOf(arg);
  const r = collect(dir);
  fs.writeFileSync(path.join(dir, 'results.json'), JSON.stringify(r, null, 2));
  const by = (k: Outcome) => r.rows.filter((x) => x.result === k).length;
  console.log(`已写入 ${path.relative(process.cwd(), path.join(dir, 'results.json'))}：${r.rows.length} 条（通过 ${by('PASSED')}，失败 ${by('FAILED')}，跳过 ${by('SKIPPED')}，阻塞 ${by('BLOCKED')}）`);
}
