/** 比对 docs/ui/design-system.md 中的令牌名与源码（src/design/tokens.ts）一致。运行：node tests/lint/tokens-doc.mjs */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { tokenNames } from '../../src/design/tokens.ts';

const here = path.dirname(fileURLToPath(import.meta.url));
const doc = fs.readFileSync(path.resolve(here, '../../../docs/ui/design-system.md'), 'utf8');
// 以 - 结尾的是前缀写法（如 --ls-color-*），不算具体令牌
const mentioned = new Set((doc.match(/--ls-[a-z0-9-]+/g) ?? []).filter((n) => !n.endsWith('-')));
const table = doc.slice(doc.indexOf('<!-- tokens:start -->'), doc.indexOf('<!-- tokens:end -->'));
const listed = new Set([...table.matchAll(/^\| `(--ls-[a-z0-9-]+)`/gm)].map((m) => m[1]));
const source = new Set(tokenNames());

const unknown = [...mentioned].filter((n) => !source.has(n));
const missing = [...source].filter((n) => !listed.has(n));
const extra = [...listed].filter((n) => !source.has(n));
if (unknown.length || missing.length || extra.length) {
  if (unknown.length) console.error('文档提到但源码没有：' + unknown.join(' '));
  if (missing.length) console.error('源码有但令牌清单缺少：' + missing.join(' '));
  if (extra.length) console.error('令牌清单多出：' + extra.join(' '));
  process.exit(1);
}
console.log(`✓ 文档与源码一致：${source.size} 个令牌`);
