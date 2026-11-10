/**
 * 全部端到端用例（无头）依次运行：19.1 黄金场景（无 BOM 生产、维修、质量整改、借还台账、培训、咨询、交接班、DCS 报警）与平台用例。
 * 用例之间暂停 E2E_PAUSE_SECONDS（默认 20）秒，避免连续登录触发接口限流。失败时继续跑完并汇总。
 * 用法：node tests/e2e/run-all.mjs [--only a,b] [--with-dcs]
 */
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const ALL = ['form-designer', 'form-designer-perf', 'flow-api', 'flow-designer', 'metrics-dashboard', 'workbench', 'pack-lifecycle',
  'production-ops', 'asset-ops', 'quality-ops', 'toolloan-config', 'training-exam', 'qualification', 'consultation', 'frappe-conversion',
  'agent-gateway', 'agent-tool-sync', 'agent-summary', 'knowledge-qa', 'access-matrix', 'ui-usage', 'menu-groups', 'global-search'];
const only = process.argv.includes('--only') ? process.argv[process.argv.indexOf('--only') + 1].split(',') : null;
const list = (only ?? ALL).concat(process.argv.includes('--with-dcs') ? ['dcs-alarm'] : []);
const pause = Number(process.env.E2E_PAUSE_SECONDS ?? 20);
const results = [];
for (const [i, t] of list.entries()) {
  if (i > 0) await new Promise((r) => setTimeout(r, pause * 1000));
  const t0 = Date.now();
  const r = spawnSync('node', [path.join(here, `${t}.e2e.mjs`)], { encoding: 'utf8', env: process.env, maxBuffer: 64 * 1024 * 1024 });
  const out = (r.stdout ?? '') + (r.stderr ?? '');
  const checks = (out.match(/^✓ /gm) ?? []).length;
  const fail = r.status !== 0 ? (out.match(/断言失败：.*|Error: .*/) ?? ['退出码 ' + r.status])[0] : null;
  results.push({ t, ok: !fail, checks, secs: Math.round((Date.now() - t0) / 1000), fail });
  console.log(`${fail ? '✗' : '✓'} ${t}（${checks} 项，${Math.round((Date.now() - t0) / 1000)}s）${fail ? '：' + fail : ''}`);
  if (fail) console.log(out.split('\n').slice(-30).join('\n'));
}
const bad = results.filter((r) => !r.ok);
console.log(`\n共 ${results.length} 个用例，通过 ${results.length - bad.length}，失败 ${bad.length}；检查项 ${results.reduce((a, r) => a + r.checks, 0)}`);
process.exit(bad.length ? 1 : 0);
