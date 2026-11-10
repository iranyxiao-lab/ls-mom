/**
 * 口令泄露检查：在给定的文件或标准输入中查找验收账号口令与演示账号口令，找到即失败（只报告位置，不输出口令）。
 * 运行：node tools/leak-check.ts 文件...；或 命令 | node tools/leak-check.ts -
 */
import fs from 'node:fs';
import path from 'node:path';
import { ROOT } from './lib.ts';
import { accPassword } from './seed-sql.ts';

export function secrets(): string[] {
  const out = [accPassword()];
  const readme = path.join(ROOT, 'deploy/seed/README.md');
  if (fs.existsSync(readme)) {
    for (const l of fs.readFileSync(readme, 'utf8').split('\n')) {
      const m = /^\| (admin|demo_[a-z_]+) \|/.exec(l);
      if (m) out.push(l.split('|')[4].trim());
    }
  }
  return [...new Set(out.filter((s) => s.length >= 6))];
}

export function findLeaks(text: string, list = secrets()): number[] {
  const lines = text.split('\n');
  const hits: number[] = [];
  lines.forEach((l, i) => {
    if (list.some((s) => l.includes(s))) hits.push(i + 1);
  });
  return hits;
}

if (import.meta.main) {
  const args = process.argv.slice(2);
  let bad = 0;
  for (const a of args.length ? args : ['-']) {
    const text = a === '-' ? fs.readFileSync(0, 'utf8') : fs.readFileSync(a, 'utf8');
    const hits = findLeaks(text);
    if (hits.length) {
      bad += hits.length;
      console.error(`发现口令：${a === '-' ? '标准输入' : a} 第 ${hits.slice(0, 10).join('、')} 行`);
    }
  }
  if (bad) process.exit(1);
  console.log('未发现口令');
}
