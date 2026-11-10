/**
 * 准入检查（acceptance-walkthrough 7.1，spec quality/acceptance-signoff「走查准入」）：开始正式走查前确认
 *   1. 被验收 change 的实施任务全部完成（tasks.md 没有未勾选项）；
 *   2. 当前提交的 CI 全绿（gh run list，需已登录 gh）；
 *   3. 可一键重置（执行 deploy/scripts/acceptance-reset.sh 并取得数据指纹）；
 *   4. 覆盖门禁已切换为阻断（acceptance.yml 中 ACCEPTANCE_BLOCKING=true）且通过。
 * 运行：node tools/entry-check.ts [--no-reset]；全部满足时输出"准入检查：通过"，否则列出原因，退出码 1。
 */
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { config, loadCases, loadScenarios, ROOT } from './lib.ts';
import { failed, trace } from './trace.ts';

export interface Item {
  name: string;
  ok: boolean;
  detail: string;
}

/** tasks.md 中未勾选的任务 */
export function openTasks(md: string): string[] {
  return md
    .split('\n')
    .filter((l) => /^\s*- \[ \]/.test(l))
    .map((l) => l.replace(/^\s*- \[ \]\s*/, '').slice(0, 60));
}

export function tasksItem(changes: string[], read = (c: string) => fs.readFileSync(path.join(ROOT, 'openspec/changes', c, 'tasks.md'), 'utf8')): Item {
  const open = changes.flatMap((c) => openTasks(read(c)).map((t) => `${c}：${t}`));
  return { name: '实施任务完成', ok: open.length === 0, detail: open.length ? `未完成 ${open.length} 项：${open.join('；')}` : `${changes.join('、')} 全部完成` };
}

/** 当前提交的各工作流最近一次运行都成功 */
export function ciItem(runs: { workflowName: string; conclusion: string; status: string }[] | null, sha: string): Item {
  if (runs === null) return { name: 'CI 全绿', ok: false, detail: '无法确认：gh 不可用或未登录' };
  if (!runs.length) return { name: 'CI 全绿', ok: false, detail: `提交 ${sha.slice(0, 7)} 没有 CI 运行记录（尚未推送？）` };
  const latest = new Map<string, { conclusion: string; status: string }>();
  for (const r of runs) if (!latest.has(r.workflowName)) latest.set(r.workflowName, r);
  const bad = [...latest].filter(([, r]) => r.status !== 'completed' || r.conclusion !== 'success').map(([n, r]) => `${n}（${r.conclusion || r.status}）`);
  return { name: 'CI 全绿', ok: bad.length === 0, detail: bad.length ? `未通过：${bad.join('、')}` : `${latest.size} 个工作流全部成功（${sha.slice(0, 7)}）` };
}

export function gateItem(workflow: string, traceFailed: boolean): Item {
  const blocking = /ACCEPTANCE_BLOCKING:\s*'?true'?/.test(workflow);
  const reasons = [!blocking && '覆盖门禁仍为"只报告"（ACCEPTANCE_BLOCKING 不是 true）', traceFailed && '覆盖门禁未通过（见 pnpm trace）'].filter(Boolean);
  return { name: '覆盖门禁阻断且通过', ok: reasons.length === 0, detail: reasons.length ? reasons.join('；') : '已切换为阻断，覆盖 100%、无悬空、P0 均有 AUTO、六个切片都有旅程' };
}

function ghRuns(sha: string) {
  try {
    const out = execFileSync('gh', ['run', 'list', '--commit', sha, '--limit', '50', '--json', 'workflowName,conclusion,status'], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
    return JSON.parse(out);
  } catch {
    return null;
  }
}

if (import.meta.main) {
  const noReset = process.argv.includes('--no-reset');
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8' }).trim();
  const items: Item[] = [tasksItem(config().changes), ciItem(ghRuns(sha), sha)];
  if (noReset) items.push({ name: '可一键重置', ok: false, detail: '未验证（--no-reset）' });
  else {
    const r = spawnSync(path.join(ROOT, 'deploy/scripts/acceptance-reset.sh'), [], { cwd: ROOT, encoding: 'utf8', timeout: 20 * 60 * 1000 });
    const fp = /fingerprint=(\w+)/.exec(r.stdout ?? '')?.[1];
    items.push({ name: '可一键重置', ok: r.status === 0 && !!fp, detail: r.status === 0 && fp ? `重置成功，数据指纹 ${fp.slice(0, 12)}…` : `重置失败（退出码 ${r.status}）` });
  }
  items.push(gateItem(fs.readFileSync(path.join(ROOT, '.github/workflows/acceptance.yml'), 'utf8'), failed(trace(loadScenarios(), loadCases()))));
  for (const i of items) console.log(`${i.ok ? '✓' : '✗'} ${i.name}：${i.detail}`);
  const pass = items.every((i) => i.ok);
  console.log(`准入检查：${pass ? '通过' : '不通过'}`);
  process.exit(pass ? 0 : 1);
}
