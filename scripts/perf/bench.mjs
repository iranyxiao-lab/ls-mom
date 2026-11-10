/**
 * 19.5 性能基准（spec platform/project-baseline「非功能基线」）：
 *   列表：通用台账查询（过滤＋排序＋分页＋总数），并发 LIST_CONCURRENCY（默认 50），P95 ≤ 800ms；
 *   保存：通用台账新增，并发 SAVE_CONCURRENCY（默认 30），P95 ≤ 500ms。
 * 以真实登录令牌走 HTTP（经 nginx → 应用 → 数据库）。输出 JSON 报告（--out），未达标时退出码为 1。
 * 用法：LSMOM_E2E_BASE=http://127.0.0.1:18097 node scripts/perf/bench.mjs --concept acme.bench.Visit --rows 1000000 --out report.json
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const { open, BASE, API, CLIENT_ID } = await import(path.join(here, '../../plus-ui/tests/e2e/lib.mjs'));

const arg = (k, d) => {
  const i = process.argv.indexOf('--' + k);
  return i > 0 ? process.argv[i + 1] : d;
};
const concept = arg('concept', 'acme.bench.Visit');
const rows = Number(arg('rows', '1000000'));
const out = arg('out', 'perf-report.json');
const seconds = Number(process.env.BENCH_SECONDS ?? 60);
const warmup = Number(process.env.BENCH_WARMUP_SECONDS ?? 10);
const LIST_C = Number(process.env.LIST_CONCURRENCY ?? 50);
const SAVE_C = Number(process.env.SAVE_CONCURRENCY ?? 30);
const LIMITS = { list: 800, save: 500 };

const s = await open({ user: process.env.BENCH_USER ?? 'admin' });
const token = await s.page.evaluate(() => localStorage.getItem('Admin-Token'));
await s.browser.close();
const headers = { 'Content-Type': 'application/json', clientid: CLIENT_ID, Authorization: 'Bearer ' + token };
const url = (p) => BASE + API + p;
const companies = ['华东检测站', '某供应商', '设备厂家', '外协单位甲', '外协单位乙', '物流公司', '设计院', '审计组'];

function listBody(i) {
  // 典型列表：按来访单位过滤、按来访日期倒序、翻前 50 页
  return { filters: [{ field: 'company', op: 'eq', value: companies[i % companies.length] }], sort: [{ field: 'visitDate', dir: 'desc' }],
    page: 1 + (i % 50), size: 20 };
}
let seq = 0;
function saveBody() {
  const n = ++seq;
  return { visitorName: '压测访客' + n, company: companies[n % companies.length], hostName: '张工', visitDate: '2026-11-10', purpose: '基准测试' };
}

async function call(kind, i) {
  const t0 = performance.now();
  const r = await fetch(kind === 'list' ? url(`/api/crud/${encodeURIComponent(concept)}/query`) : url(`/api/crud/${encodeURIComponent(concept)}`), {
    method: 'POST', headers, body: JSON.stringify(kind === 'list' ? listBody(i) : saveBody())
  });
  const text = await r.text();
  let body = {};
  try { body = JSON.parse(text); } catch { /* 非 JSON 响应（网关错误等） */ }
  return { ms: performance.now() - t0, ok: r.status === 200 && body.code === 200, msg: body.msg ?? `HTTP ${r.status} ${text.slice(0, 120)}` };
}

async function phase(kind, concurrency) {
  const lat = [];
  let errors = 0, lastError = null, i = 0;
  const stopWarm = Date.now() + warmup * 1000;
  const stop = stopWarm + seconds * 1000;
  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (Date.now() < stop) {
      const r = await call(kind, i++);
      if (Date.now() < stopWarm) continue;
      if (r.ok) lat.push(r.ms);
      else { errors++; lastError = r.msg; }
    }
  }));
  lat.sort((a, b) => a - b);
  const q = (p) => (lat.length ? Math.round(lat[Math.min(lat.length - 1, Math.floor((p / 100) * lat.length))]) : null);
  const res = { kind, concurrency, seconds, requests: lat.length, errors, lastError, throughput: Math.round(lat.length / seconds),
    p50: q(50), p95: q(95), p99: q(99), max: lat.length ? Math.round(lat[lat.length - 1]) : null, limitP95: LIMITS[kind] };
  res.passed = errors === 0 && res.p95 !== null && res.p95 <= LIMITS[kind];
  console.log(`${kind === 'list' ? '列表' : '保存'}：并发 ${concurrency}，${res.requests} 次，${res.throughput}/s，P50 ${res.p50}ms，P95 ${res.p95}ms（≤${LIMITS[kind]}），P99 ${res.p99}ms，错误 ${errors}${lastError ? '（' + lastError + '）' : ''} → ${res.passed ? '达标' : '未达标'}`);
  return res;
}

const list = await phase('list', LIST_C);
const save = await phase('save', SAVE_C);
const report = { at: new Date().toISOString(), base: BASE, concept, rows, list, save, passed: list.passed && save.passed };
fs.writeFileSync(out, JSON.stringify(report, null, 2));
console.log(report.passed ? '性能基准达标' : '性能基准未达标');
process.exit(report.passed ? 0 : 1);
