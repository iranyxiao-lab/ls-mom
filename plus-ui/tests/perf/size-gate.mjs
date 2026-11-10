/**
 * 包体门禁自检（spec ui/ux-quality「性能预算 ▸ 包体超标」）：
 *   1. 当前构建的首屏 JS（gzip）在 400 kB 预算内，size-limit 通过；
 *   2. 预算收紧到低于实际体积时 size-limit 失败（模拟"某次提交使首屏 JS 超过预算"）；
 *   3. 失败时 CI 运行的 bundle-why 按 npm 包 / 源码目录列出首屏构成与相对基线的增长来源。
 * 需要先构建：pnpm build:prod（第 3 步自行以 sourcemap 构建到 dist-analyze）。运行：node tests/perf/size-gate.mjs
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ui = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const run = (cmd, args, env = {}) => {
  const r = spawnSync(cmd, args, { cwd: ui, encoding: 'utf8', env: { ...process.env, ...env }, maxBuffer: 64 * 1024 * 1024 });
  return { status: r.status, out: (r.stdout ?? '') + (r.stderr ?? '') };
};
const check = (ok, msg) => {
  if (!ok) {
    console.error('断言失败：' + msg);
    process.exit(1);
  }
  console.log('✓ ' + msg);
};

if (!fs.existsSync(path.join(ui, 'dist/index.html'))) check(run('pnpm', ['build:prod']).status === 0, '构建 dist');
const ok = run('pnpm', ['exec', 'size-limit']);
const size = /Size:\s+([\d.]+ kB)/.exec(ok.out)?.[1];
check(ok.status === 0, `首屏 JS（gzip）${size} 在 400 kB 预算内`);
const tight = run('pnpm', ['exec', 'size-limit'], { LS_FIRST_SCREEN_LIMIT: '100 kB' });
check(tight.status !== 0 && /limit|exceeds|超/i.test(tight.out), '首屏 JS 超过预算时包体检查失败');
const build = run('pnpm', ['exec', 'vite', 'build', '--mode', 'production', '--sourcemap', '--outDir', 'dist-analyze']);
check(build.status === 0, '以 sourcemap 构建分析包');
const why = run('node', ['tests/perf/bundle-why.mjs', 'dist-analyze']);
const lines = why.out.split('\n').filter((l) => /^\s+[\d.]+\s/.test(l));
check(why.status === 0 && why.out.includes('首屏脚本构成') && lines.length > 3, `列出首屏构成与增长来源（${lines.length} 项）`);
fs.rmSync(path.join(ui, 'dist-analyze'), { recursive: true, force: true });
