/**
 * 可用性测试结果导入：解析 docs/ui/usability-report.md「5.1 完成率与耗时」表，写 runs/<run>/usability.json（report.ts 读取）。
 * 运行：node tools/usability.ts <run> [报告路径]
 * 耗时中位数支持「95」「95s」「1:35」「1分35秒」写法；未填参与人数的行跳过。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import type { Usability } from './report.ts';

export function seconds(s: string): number | undefined {
  const t = s.trim();
  if (!t) return undefined;
  let m = t.match(/^(\d+):(\d{1,2})$/);
  if (m) return Number(m[1]) * 60 + Number(m[2]);
  m = t.match(/^(?:(\d+)\s*分(?:钟)?)?\s*(?:(\d+)\s*秒)?$/);
  if (m && (m[1] || m[2])) return Number(m[1] ?? 0) * 60 + Number(m[2] ?? 0);
  m = t.match(/^(\d+(?:\.\d+)?)\s*s?$/i);
  return m ? Number(m[1]) : undefined;
}

export function parseUsability(md: string): Usability {
  const lines = md.split('\n');
  const start = lines.findIndex((l) => /^\|\s*任务\s*\|\s*角色\s*\|\s*参与人数/.test(l));
  if (start < 0) throw new Error('找不到「任务 | 角色 | 参与人数」结果表');
  const tasks: Usability['tasks'] = [];
  for (const l of lines.slice(start + 2)) {
    if (!l.startsWith('|')) break;
    const [task, role, participants, completed, , median] = l.split('|').slice(1, -1).map((c) => c.trim());
    if (!participants) continue;
    const p = Number(participants);
    const c = Number(completed);
    if (!Number.isInteger(p) || !Number.isInteger(c) || c < 0 || c > p) throw new Error(`结果行数字无效：${l}`);
    const medianSec = seconds(median ?? '');
    tasks.push({ task, role, participants: p, completed: c, ...(medianSec !== undefined ? { medianSec } : {}) });
  }
  return { tasks };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [run, file = path.resolve(import.meta.dirname, '../../docs/ui/usability-report.md')] = process.argv.slice(2);
  if (!run) throw new Error('用法：node tools/usability.ts <run> [报告路径]');
  const u = parseUsability(fs.readFileSync(file, 'utf8'));
  if (!u.tasks.length) throw new Error('结果表尚未填写');
  const out = path.resolve(import.meta.dirname, '../runs', run, 'usability.json');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, JSON.stringify(u, null, 2) + '\n');
  for (const t of u.tasks) console.log(`${t.task} ${t.role}：${t.completed}/${t.participants}${t.medianSec !== undefined ? `，中位 ${t.medianSec}s` : ''}`);
  console.log(`已写 ${path.relative(process.cwd(), out)}`);
}
