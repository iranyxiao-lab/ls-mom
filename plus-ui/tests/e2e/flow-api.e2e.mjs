/**
 * 9.x 部署环境实测（无头）：流程接口闭环。
 * 发布「不符合项审核」流程（ls.core.Issue，提交即发起）→ 登记并提交两条不符合项 →
 * 待办出现 → 一条审核通过（回调开始整改）、一条驳回（回调驳回，原因取审核意见）→ 业务状态、时间线、对账无差异。
 * 运行：node tests/e2e/flow-api.e2e.mjs（LSMOM_E2E_BASE 指定地址；只用于测试环境）
 */
import { open, check } from './lib.mjs';

const { browser, api, errors } = await open();
try {
  const me = await api('GET', '/system/user/getInfo');
  const userId = me.data.user.userId;
  const orgs = await api('GET', '/api/flow/me');
  const party = orgs.code === 200 ? orgs.data.partyId : null;
  check(party, '当前登录用户映射到 Party ' + party + '（用户 ' + userId + '）');
  const org = orgs.data.orgUnitId;

  const code = 'acme.flowlive' + Date.now().toString(36) + '.issueAudit';
  const i = 'ls.core.Issue.';
  const def = {
    code,
    name: '不符合项审核',
    concept: 'ls.core.Issue',
    startOn: i + 'submit',
    orgUnitId: org,
    callbacks: { approved: i + 'startRectification', rejected: i + 'reject' },
    nodes: [{ key: 'audit', type: 'AUDIT', name: '质量审核', assignee: { strategy: 'USER', value: [party] } }]
  };
  const v = await api('POST', '/api/flow/definitions/validate', def);
  check(v.code === 200 && v.data.length === 0, '流程校验无问题 ' + JSON.stringify(v.data ?? v.msg));
  const bad = await api('POST', '/api/flow/definitions/validate', { ...def, callbacks: { approved: i + 'startRectification' } });
  check(
    bad.data.some((x) => x.code === 'CALLBACK_REQUIRED'),
    '缺少驳回回调被校验拦截'
  );
  const pv = await api('POST', '/api/flow/definitions/preview', def);
  check(
    pv.code === 200 && pv.data.bpmnXml.includes('userTask') && !pv.data.bpmnXml.includes('scriptTask'),
    '预览生成 BPMN（含用户任务、无脚本任务）'
  );
  const pub = await api('POST', '/api/flow/definitions/publish', def);
  check(pub.code === 200 && pub.data.version === 1 && pub.data.status === 'PUBLISHED', '发布流程 v1：' + (pub.msg ?? ''));

  async function raise(title) {
    const r = await api('POST', '/api/op/' + i + 'raise', { input: { type: 'DEFECT', title, severity: 'MEDIUM', orgUnitId: org } });
    check(r.code === 200, '登记不符合项「' + title + '」');
    const id = r.data.targetId;
    const a = await api('GET', `/api/op/allowed?concept=ls.core.Issue&id=${id}`);
    const s = await api('POST', '/api/op/' + i + 'submit', { input: {}, targetId: id, version: a.data.version });
    check(s.code === 200, '提交不符合项 ' + id + '：' + (s.msg ?? ''));
    return id;
  }
  const status = async (id) => (await api('GET', `/api/op/allowed?concept=ls.core.Issue&id=${id}`)).data.status;
  const waitTask = async (id) => {
    for (let n = 0; n < 30; n++) {
      const t = (await api('GET', '/api/flow/todo')).data.find((x) => x.bizType === 'ls.core.Issue' && x.bizId === id && x.status === 'TODO');
      if (t) return t;
      await new Promise((r) => setTimeout(r, 300));
    }
    return null;
  };

  const okId = await raise('包装破损（应通过）');
  const noId = await raise('标签模糊（应驳回）');
  const t1 = await waitTask(okId);
  const t2 = await waitTask(noId);
  check(t1 && t1.nodeKey === 'audit' && t2, '提交后自动发起流程，待办列表出现两条「质量审核」');
  check((await status(okId)) === 'OPEN', '流程中业务状态仍由业务表决定：OPEN');

  const other = await api('POST', `/api/flow/tasks/${t1.taskId}/complete`, { action: 'BOGUS' });
  check(other.code !== 200, '非法办理动作被拒');

  const c1 = await api('POST', `/api/flow/tasks/${t1.taskId}/complete`, { action: 'APPROVE', comment: '同意整改' });
  check(c1.code === 200 && c1.data.outcome === 'APPROVED', '审核通过');
  const c2 = await api('POST', `/api/flow/tasks/${t2.taskId}/complete`, { action: 'REJECT', comment: '描述不清，重新登记' });
  check(c2.code === 200 && c2.data.outcome === 'REJECTED', '审核驳回');

  const settle = async (id, want) => {
    for (let n = 0; n < 30; n++) {
      if ((await status(id)) === want) return true;
      await new Promise((r) => setTimeout(r, 300));
    }
    return false;
  };
  check(await settle(okId, 'IN_RECTIFICATION'), '通过回调 startRectification 把不符合项迁移到 IN_RECTIFICATION');
  check(await settle(noId, 'REJECTED'), '驳回回调 reject 把不符合项迁移到 REJECTED');

  const biz = await api('GET', `/api/flow/biz/ls.core.Issue/${okId}`);
  check(biz.data.instances[0].instance.status === 'APPROVED', '流程实例状态 APPROVED');
  const tl = await api('GET', `/api/timeline/ls.core.Issue/${okId}`);
  const types = JSON.stringify(tl.data);
  check(
    ['ls.flow.started', 'ls.flow.task.approved', 'ls.flow.approved'].every((x) => types.includes(x)),
    '时间线包含 started / task.approved / approved'
  );
  const rej = JSON.stringify((await api('GET', `/api/timeline/ls.core.Issue/${noId}`)).data);
  check(rej.includes('重新登记'), '驳回原因来自审核意见');

  const again = await api('POST', `/api/flow/tasks/${t1.taskId}/complete`, { action: 'APPROVE' });
  check(again.code !== 200, '已办待办不能再次办理');

  const rec = await api('GET', '/api/flow/reconcile');
  const mine = rec.data.diffs.filter((d) => JSON.stringify(d).includes(okId) || JSON.stringify(d).includes(noId));
  check(rec.code === 200 && mine.length === 0, `对账：检查 ${rec.data.checkedInstances} 个实例，本次实例无差异`);
  check(errors.length === 0, '页面无脚本错误');
} finally {
  await browser.close();
}
