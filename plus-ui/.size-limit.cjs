/**
 * 首屏 JS 体积预算（spec ui/ux-quality「性能预算」）：dist/index.html 直接加载的脚本（入口与 modulepreload），gzip ≤400KB。
 * 其余按路由懒加载，不计入。超预算时 tests/perf/bundle-why.mjs 列出增长来源。
 */
const fs = require('node:fs');

const html = fs.readFileSync('dist/index.html', 'utf8');
const files = [...html.matchAll(/(?:src|href)="\/(assets\/[^"]+\.js)"/g)].map((m) => 'dist/' + m[1]);

module.exports = [
  {
    name: '首屏 JS（gzip）',
    path: files,
    gzip: true,
    limit: process.env.LS_FIRST_SCREEN_LIMIT ?? '400 kB'
  }
];
