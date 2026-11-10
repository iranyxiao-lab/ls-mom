/**
 * 样式门禁自检：示例违规（十六进制颜色、裸 px 间距）在业务页面路径下必须报错，使用令牌的写法必须通过；
 * 然后对 src/views 全量检查。运行：node tests/lint/style-gate.mjs
 */
import stylelint from 'stylelint';

const bad = `<template><div class="x" /></template>
<style scoped>
.x { color: #ff0000; margin: 12px 8px; gap: 4px; }
</style>`;
const good = `<template><div class="x" /></template>
<style scoped>
.x { color: var(--ls-color-danger); margin: var(--ls-space-3) var(--ls-space-2); gap: var(--ls-space-1); border: 1px solid var(--ls-color-border); width: 240px; }
</style>`;

const lint = (code, codeFilename) => stylelint.lint({ code, codeFilename });
const fail = (m) => {
  console.error('✗ ' + m);
  process.exit(1);
};

const r1 = await lint(bad, 'src/views/lsmom/sample/Bad.vue');
const rules = r1.results[0].warnings.map((w) => w.rule);
if (!r1.errored || !rules.includes('color-no-hex') || rules.filter((r) => r === 'declaration-property-unit-disallowed-list').length < 2) {
  fail('示例违规文件应 lint 失败：' + JSON.stringify(rules));
}
console.log(`✓ 示例违规文件 lint 失败（${r1.results[0].warnings.length} 处：${[...new Set(rules)].join('、')}）并指出位置：` +
  r1.results[0].warnings.map((w) => `${w.line}:${w.column}`).join(' '));
const r2 = await lint(good, 'src/views/lsmom/sample/Good.vue');
if (r2.errored) fail('使用令牌的写法不应报错：' + JSON.stringify(r2.results[0].warnings));
console.log('✓ 使用令牌的写法通过');
const r3 = await lint(bad, 'src/components/Sample.vue');
if (r3.errored) fail('门禁只约束业务页面（src/views）');
console.log('✓ 只约束业务页面');

const all = await stylelint.lint({ files: 'src/views/**/*.{vue,scss,css}' });
const n = all.results.reduce((s, r) => s + r.warnings.length, 0);
if (all.errored) {
  for (const r of all.results) for (const w of r.warnings) console.error(`${r.source.replace(process.cwd() + '/', '')}:${w.line}:${w.column} ${w.text}`);
  fail(`src/views 中有 ${n} 处违规`);
}
console.log('✓ src/views 无违规');
