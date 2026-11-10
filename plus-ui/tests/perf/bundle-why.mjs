/**
 * 首屏包构成与增长来源：按源码映射把首屏脚本的压缩后字节归到 npm 包 / 源码目录，并与基线比较。
 * 用法：npx vite build --mode production --sourcemap --outDir dist-analyze && node tests/perf/bundle-why.mjs dist-analyze [--update]
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const dir = process.argv[2] ?? 'dist-analyze';
const update = process.argv.includes('--update');
// source-map-js 是 vite → postcss 的依赖（pnpm 下需沿真实路径逐级解析）
const fromVite = createRequire(fs.realpathSync('node_modules/vite/package.json'));
const fromPostcss = createRequire(fromVite.resolve('postcss/package.json'));
const { SourceMapConsumer } = fromPostcss('source-map-js');
const baselineFile = 'tests/perf/bundle-baseline.json';

const html = fs.readFileSync(path.join(dir, 'index.html'), 'utf8');
const files = [...html.matchAll(/(?:src|href)="\/(assets\/[^"]+\.js)"/g)].map((m) => m[1]);
const by = {};
for (const f of files) {
  const map = path.join(dir, f + '.map');
  if (!fs.existsSync(map)) throw new Error('缺少源码映射：' + map + '（构建时加 --sourcemap）');
  const c = new SourceMapConsumer(JSON.parse(fs.readFileSync(map, 'utf8')));
  let prev = null;
  c.eachMapping((m) => {
    if (prev && prev.generatedLine === m.generatedLine) {
      const s = prev.source ?? '?';
      let k;
      if (s.includes('node_modules/')) {
        const p = s.split('node_modules/').pop().split('/');
        k = p[0].startsWith('@') ? `${p[0]}/${p[1]}` : p[0];
      } else k = 'src/' + s.replace(/^.*?src\//, '').split('/').slice(0, 2).join('/');
      by[k] = (by[k] ?? 0) + (m.generatedColumn - prev.generatedColumn);
    }
    prev = m;
  });
}
const kb = (n) => Math.round(n / 102.4) / 10;
const rows = Object.entries(by).sort((a, b) => b[1] - a[1]);
const base = fs.existsSync(baselineFile) ? JSON.parse(fs.readFileSync(baselineFile, 'utf8')) : {};
console.log('首屏脚本构成（压缩后、未 gzip，KB）与基线差异：');
for (const [k, v] of rows.slice(0, 25)) {
  const d = kb(v) - (base[k] ?? 0);
  console.log(`${String(kb(v)).padStart(8)}  ${d > 0.5 ? ('+' + d.toFixed(1)).padStart(8) : ''.padStart(8)}  ${k}${base[k] == null ? '（新增）' : ''}`);
}
if (update) {
  fs.writeFileSync(baselineFile, JSON.stringify(Object.fromEntries(rows.filter(([, v]) => v > 1024).map(([k, v]) => [k, kb(v)])), null, 2) + '\n');
  console.log('已更新基线 ' + baselineFile);
}
