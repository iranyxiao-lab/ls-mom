/**
 * 签收记录只增不改：比较基线提交与当前提交，acceptance/signoff/ 下已有文件被修改、删除或改名时失败。
 * 运行：node tools/signoff-guard.ts <基线提交>（CI 传 PR 的 base 或 push 前的提交；为空或全 0 时跳过）
 */
import { execFileSync } from 'node:child_process';
import { ROOT } from './lib.ts';

export function changedSignoffs(base: string, head = 'HEAD'): { status: string; file: string }[] {
  const out = execFileSync('git', ['diff', '--name-status', '-M', base, head, '--', 'acceptance/signoff/'], { cwd: ROOT, encoding: 'utf8' });
  return out
    .split('\n')
    .filter(Boolean)
    .map((l) => {
      const [status, ...files] = l.split('\t');
      return { status, file: files.join(' → ') };
    })
    .filter((x) => !x.status.startsWith('A') && !x.file.endsWith('README.md') && !x.file.endsWith('TEMPLATE.md'));
}

if (import.meta.main) {
  const base = process.argv[2] ?? '';
  if (!base || /^0+$/.test(base)) {
    console.log('没有基线提交，跳过签收记录检查');
    process.exit(0);
  }
  const bad = changedSignoffs(base);
  if (bad.length) {
    console.error('签收记录只能新增，不能修改历史记录：');
    for (const b of bad) console.error(`  ${b.status} ${b.file}`);
    process.exit(1);
  }
  console.log('签收记录检查通过');
}
