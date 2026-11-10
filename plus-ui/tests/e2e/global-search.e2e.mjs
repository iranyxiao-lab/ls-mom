/**
 * modernize-ui-ux 3.4 端到端（部署环境）：全局搜索读模型与命名 Query ls.ui.globalSearch。
 *   新增设备、工作后经 Outbox 进入索引；编号精确命中排第一；全拼、首字母可搜；改名、删除随之更新；
 *   班组长只搜到本班组的工作；没有设备台账列表权限的人搜不到设备。
 * 运行：node tests/e2e/global-search.e2e.mjs（LSMOM_E2E_BASE 指定地址；只用于测试环境）
 */
import { check, open } from './lib.mjs';

const ts = Date.now().toString(36);
const ASSET = 'ls.asset.Asset';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const exec = async (s, code, input) => {
  const r = await s.api('POST', `/api/op/${code}`, { input });
  if (r.code !== 200) throw new Error(`${code} 失败：${r.msg}`);
  return r.data;
};
const search = async (s, q) => {
  const r = await s.api('GET', `/api/ui/search?q=${encodeURIComponent(q)}&limit=20`);
  if (r.code !== 200) throw new Error(`搜索失败：${r.msg}`);
  return r.data;
};
/** 索引经 Outbox 异步更新：轮询直到满足条件（最多 20 秒）。 */
const until = async (s, q, ok) => {
  let rows = [];
  for (let i = 0; i < 40; i++) {
    rows = await search(s, q);
    if (ok(rows)) return rows;
    await sleep(500);
  }
  return rows;
};
const has = (rows, type, id) => rows.some((r) => r.object_type === type && r.object_id === String(id));

const admin = await open();
const team = await open({ user: 'demo_team', browser: admin.browser });
const operator = await open({ user: 'demo_operator', browser: admin.browser });
try {
  const orgs = (await admin.api('GET', '/api/asset/orgs')).data;
  const teamA = orgs.find((o) => o.name === '一车间甲班');
  const teamB = orgs.find((o) => o.name === '一车间乙班');

  // —— 设备：编号、名称、拼音 ——
  const code = `GS-${ts}`.toUpperCase();
  const asset = (await admin.api('POST', `/api/crud/${ASSET}`, { assetCode: code, name: `离心泵${ts}`, assetType: 'STATION', orgId: teamA.id })).data;
  check(asset?.id, `新增设备 ${code}`);
  const byCode = await until(admin, code, (rows) => has(rows, ASSET, asset.id));
  check(byCode[0]?.object_id === String(asset.id) && byCode[0]?.rank === 0, '按设备编号搜索：编号精确命中排第一');
  check(byCode[0]?.title === `离心泵${ts}` && byCode[0]?.subtitle, '结果带名称与对象类型');
  check(has(await search(admin, `lixinbeng${ts}`), ASSET, asset.id), '全拼可搜');
  check(has(await search(admin, `lxb${ts}`), ASSET, asset.id), '首字母可搜');
  check(has(await search(admin, code.toLowerCase().slice(0, -1)), ASSET, asset.id), '编号前缀、不区分大小写可搜');

  // 另一条名称里带着这个编号的工作排在后面
  const work = await exec(admin, 'ls.core.Work.create', { workType: 'PRODUCTION', title: `检修 ${code} 的叶轮`, orgUnitId: teamA.id });
  const mixed = await until(admin, code, (rows) => has(rows, 'ls.core.Work', work.targetId));
  check(mixed[0]?.object_id === String(asset.id) && has(mixed, 'ls.core.Work', work.targetId), '编号精确命中在前，名称中提到编号的工作在后');

  // —— 改名、删除 ——
  const cur = (await admin.api('GET', `/api/crud/${ASSET}/${asset.id}`)).data;
  await admin.api('PUT', `/api/crud/${ASSET}/${asset.id}?version=${cur.version}`, { name: `真空泵${ts}` });
  check(has(await until(admin, `zkb${ts}`, (rows) => has(rows, ASSET, asset.id)), ASSET, asset.id), '改名后按新名称首字母可搜');
  check(!has(await search(admin, `lxb${ts}`), ASSET, asset.id), '改名后旧名称搜不到');

  // —— 数据域：班组长只搜到本班组的工作 ——
  const workB = await exec(admin, 'ls.core.Work.create', { workType: 'PRODUCTION', title: `乙班巡检${ts}`, orgUnitId: teamB.id });
  const workA = await exec(admin, 'ls.core.Work.create', { workType: 'PRODUCTION', title: `甲班巡检${ts}`, orgUnitId: teamA.id });
  await until(admin, `巡检${ts}`, (rows) => has(rows, 'ls.core.Work', workA.targetId) && has(rows, 'ls.core.Work', workB.targetId));
  const mine = await search(team, `xj${ts}`);
  check(has(mine, 'ls.core.Work', workA.targetId) && !has(mine, 'ls.core.Work', workB.targetId), '班组长只搜到本班组的工作');

  // —— 对象类型权限：操作工没有生产需求台账的列表权限，搜不到生产需求 ——
  const REQ = 'ls.production.ProductionRequest';
  const perms = (await operator.api('GET', '/system/user/getInfo')).data.permissions;
  check(!perms.includes('*:*:*') && !perms.includes(`crud:${REQ}:list`), '操作工没有生产需求台账列表权限');
  const req = (await admin.api('POST', `/api/crud/${REQ}/query`, { sort: [{ field: 'id', dir: 'desc' }], page: 1, size: 1 })).data.rows[0];
  const reqHits = await search(admin, req.title ?? req.name);
  check(has(reqHits, REQ, req.id), '管理员能搜到生产需求');
  const opHits = await search(operator, req.title ?? req.name);
  check(!opHits.some((r) => r.object_type === REQ), '操作工搜同一关键词，结果里没有生产需求');

  // —— 删除后移出索引 ——
  const now = (await admin.api('GET', `/api/crud/${ASSET}/${asset.id}`)).data;
  await admin.api('DELETE', `/api/crud/${ASSET}/${asset.id}?version=${now.version}`);
  const gone = await until(admin, code, (rows) => !has(rows, ASSET, asset.id));
  check(!has(gone, ASSET, asset.id), '删除设备后搜不到');
  check((await search(admin, '   ')).length === 0, '空关键词返回空结果');
} finally {
  await admin.browser.close();
}
