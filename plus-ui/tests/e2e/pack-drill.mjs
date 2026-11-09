/**
 * 升级演练与发布门禁的接口助手（无头登录，LSMOM_E2E_BASE 指定目标环境）。由 deploy/scripts/upgrade-drill.sh、release-gate.sh 调用：
 *   node tests/e2e/pack-drill.mjs artifact <changeSetId> <out.zip>   从正式/测试环境下载 ChangeSet 制品
 *   node tests/e2e/pack-drill.mjs run <pack.zip> <report.json>        在演练环境安装制品并运行全部已启用 Pack 的测试
 *   node tests/e2e/pack-drill.mjs report <changeSetId> <report.json>  把演练结果回写到 ChangeSet 各阶段
 *   node tests/e2e/pack-drill.mjs gate <changeSetId>                  查询发布门禁，未通过时退出码为 1
 *   node tests/e2e/pack-drill.mjs fail <report.json> <stage> <json>   写入一份失败报告（演练环境未能启动时）
 */
import fs from 'node:fs';
import { open } from './lib.mjs';

const [cmd, a1, a2, a3] = process.argv.slice(2);
const STAGES = ['SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE'];

function failureOf(r) {
  return { code: r?.data?.errorCode ?? String(r?.code), message: r?.msg ?? '', migration: r?.data?.details?.migration ?? undefined };
}

if (cmd === 'fail') {
  const detail = JSON.parse(a3 ?? '{}');
  const report = { sha256: null, stages: Object.fromEntries(STAGES.map((s) => [s, { status: 'FAILED', detail }])) };
  fs.writeFileSync(a1, JSON.stringify(report, null, 2));
  process.exit(0);
}

const { browser, api } = await open();
let exit = 0;
try {
  if (cmd === 'artifact') {
    const r = await api('GET', `/api/changesets/${a1}/artifact`);
    if (r.code !== 200) throw new Error('下载制品失败：' + r.msg);
    fs.writeFileSync(a2, Buffer.from(r.data.archive, 'base64'));
    console.log(r.data.sha256);
  } else if (cmd === 'run') {
    const archive = fs.readFileSync(a1).toString('base64');
    const before = (await api('GET', '/api/packs')).data ?? [];
    const ins = await api('POST', '/api/packs/install', { archive });
    const report = { sha256: ins.data?.artifactSha ?? null, install: ins.data ?? null, stages: {} };
    if (ins.code !== 200) {
      const d = failureOf(ins);
      STAGES.forEach((s) => (report.stages[s] = { status: 'FAILED', detail: d }));
      console.log('✗ 沙箱安装失败：' + ins.msg);
      exit = 1;
    } else {
      report.stages.SANDBOX = { status: 'PASSED', detail: { result: ins.data.status, steps: ins.data.steps, previous: ins.data.previousVersion } };
      console.log(`✓ 沙箱安装 ${ins.data.code} ${ins.data.version}（${ins.data.status}）`);
      const own = await api('POST', `/api/packs/${encodeURIComponent(ins.data.code)}/test`);
      const reg = own.data?.regression ?? [];
      const pri = own.data?.privilege ?? [];
      const st = (cases) => (own.code === 200 && cases.every((c) => c.passed) ? 'PASSED' : 'FAILED');
      report.stages.REGRESSION = { status: st(reg), detail: { cases: reg, message: own.code === 200 ? undefined : own.msg } };
      report.stages.PRIVILEGE = { status: st(pri), detail: { cases: pri, message: own.code === 200 ? undefined : own.msg } };
      // 升级：恢复的是上一版本备份；安装后其余已启用 Pack（含依赖它的客户 Pack）的验收测试仍需通过
      const others = [];
      for (const p of (await api('GET', '/api/packs')).data ?? []) {
        if (p.code === ins.data.code || p.status !== 'ENABLED') continue;
        const t = await api('POST', `/api/packs/${encodeURIComponent(p.code)}/test`);
        others.push({ pack: p.code, passed: t.code === 200 && t.data.passed, message: t.code === 200 ? undefined : t.msg });
      }
      const from = before.find((p) => p.code === ins.data.code)?.version ?? null;
      report.stages.UPGRADE = {
        status: others.every((o) => o.passed) ? 'PASSED' : 'FAILED',
        detail: {
          from,
          to: ins.data.version,
          packs: others,
          message:
            others
              .filter((o) => !o.passed)
              .map((o) => o.pack + ' 测试失败')
              .join('；') || undefined
        }
      };
      for (const s of STAGES) {
        console.log(`${report.stages[s].status === 'PASSED' ? '✓' : '✗'} ${s}`);
        if (report.stages[s].status !== 'PASSED') exit = 1;
      }
    }
    fs.writeFileSync(a2, JSON.stringify(report, null, 2));
  } else if (cmd === 'report') {
    const report = JSON.parse(fs.readFileSync(a2, 'utf8'));
    const cs = await api('GET', `/api/changesets/${a1}`);
    if (cs.code !== 200) throw new Error('ChangeSet 不存在：' + cs.msg);
    for (const s of STAGES) {
      const r = report.stages[s];
      const res = await api('POST', `/api/changesets/${a1}/stages`, {
        stage: s,
        status: r.status,
        artifactSha: cs.data.artifactSha,
        detail: r.detail
      });
      if (res.code !== 200) throw new Error(`回写 ${s} 失败：${res.msg}`);
    }
    const after = await api('GET', `/api/changesets/${a1}`);
    console.log(`ChangeSet #${a1} 状态：${after.data.status}`);
  } else if (cmd === 'gate') {
    const g = await api('GET', `/api/changesets/${a1}/gate`);
    if (g.code !== 200) throw new Error(g.msg);
    if (g.data.ready) {
      console.log(`✓ ChangeSet #${a1} 门禁通过`);
    } else {
      console.log(`✗ ChangeSet #${a1} 门禁未通过：`);
      g.data.problems.forEach((p) => console.log('  - ' + p));
      exit = 1;
    }
  } else {
    console.error('未知命令：' + cmd);
    exit = 2;
  }
} finally {
  await browser.close();
}
process.exit(exit);
