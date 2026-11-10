-- 验收种子数据集（acceptance-walkthrough 2.1）：在演示种子（组织、角色、字典）之上增加固定的验收账号、设备、点检计划、DCS 测点与 Agent 服务身份。
-- 由 deploy/scripts/acceptance-reset.sh 在全新库上执行；可重复执行（按编码判重），连续两次重置结果一致。
-- 账号段由 acceptance/tools/seed-sql.ts 根据 accounts.yaml 生成并拼接在本文件之前（:acc_hash 为口令散列）。

-- 组织负责人：验收车间主任、班组长、设备科与质量科负责人（负责人解析优先取 core_org_unit.leader_party_id）
update core_org_unit o set leader_party_id = p.id
  from (values (2010, 2403), (2020, 2404), (2011, 2405), (2030, 2408), (2040, 2410)) v(dept, usr)
  join core_party p on p.user_id = v.usr
 where o.dept_id = v.dept;

-- 设备（所属班组按 sys_dept 映射解析，负责人为验收设备员）
insert into c_ls_asset_asset (org_id, status, asset_code, name, asset_type, location, owner, model, commissioned_on, created_by)
select o.id, 'RUNNING', v.code, v.name, v.type, v.loc,
       (select id from core_party where user_id = 2408), v.model, date '2024-03-01', 1
  from (values ('ACC-EQ-001', '一号空压机（验收）', 'PUMP',    '一车间东侧', 'GA-37',  2011),
               ('ACC-EQ-002', '二号焊机（验收）',   'STATION', '二车间焊接区', 'NBC-500', 2021),
               ('ACC-EQ-003', '三号循环泵（验收）', 'PUMP',    '一车间泵房', 'IS-80',  2012)) v(code, name, type, loc, model, dept)
  join core_org_unit o on o.dept_id = v.dept
 where not exists (select 1 from c_ls_asset_asset a where a.asset_code = v.code);

-- 点检计划：一号空压机日点检（操作工负责）
insert into c_ls_asset_inspection_plan (org_id, status, name, asset, cycle, responsible, next_due, created_by)
select a.org_id, 'ACTIVE', '一号空压机日点检（验收）', a.id, 'DAILY', (select id from core_party where user_id = 2406), current_date, 1
  from c_ls_asset_asset a
 where a.asset_code = 'ACC-EQ-001'
   and not exists (select 1 from c_ls_asset_inspection_plan p where p.name = '一号空压机日点检（验收）');

insert into c_ls_asset_inspection_plan_item (parent_id, line_no, item_code, name, method, standard, value_type, unit, lower_limit, upper_limit)
select p.id, v.line, v.code, v.name, v.method, v.std, v.vt, v.unit, v.lo, v.hi
  from c_ls_asset_inspection_plan p
  cross join (values (1, 'TEMP', '排气温度', '读表', '60～95 ℃', 'NUMBER', '℃', 60::numeric, 95::numeric),
                     (2, 'NOISE', '运行异响', '听', '无异响', 'CHECK', null, null, null)) v(line, code, name, method, std, vt, unit, lo, hi)
 where p.name = '一号空压机日点检（验收）'
   and not exists (select 1 from c_ls_asset_inspection_plan_item i where i.parent_id = p.id and i.item_code = v.code);

-- DCS 测点（只读接入，报警策略：空压机温度自动生成缺陷草稿，焊机电流只通知）
insert into dcs_point (code, name, unit, range_low, range_high, source_system, asset_id, org_unit_id, process_name, alarm_policy, owner_party, enabled, created_by)
select v.code, v.name, v.unit, v.lo, v.hi, 'DCS-A', a.id, a.org_id, v.proc, v.policy, (select id from core_party where user_id = 2408), true, 1
  from (values ('ACC-PT-001', '空压机排气温度（验收）', '℃', 0::numeric, 150::numeric, '压缩空气', 'AUTO_DRAFT', 'ACC-EQ-001'),
               ('ACC-PT-002', '焊机电流（验收）',       'A', 0::numeric, 600::numeric, '焊接',     'NOTIFY',     'ACC-EQ-002')) v(code, name, unit, lo, hi, proc, policy, asset)
  join c_ls_asset_asset a on a.asset_code = v.asset
on conflict (code) do nothing;

-- Agent 服务身份（第 8 类角色）：只能读取工作与登记异常草稿，用于越权与禁止能力的负向走查
insert into core_party (code, party_type, name)
values ('AGENT-ACC', 'AGENT', '验收助手')
on conflict (code) do nothing;

insert into agent_identity (code, name, party_id, permissions, rate_limit_per_minute, description)
select 'acc-agent', '验收助手', p.id, '["lsmom:work:execute","lsmom:issue:raise"]'::jsonb, 60, '验收走查用：读取工作、登记异常草稿'
  from core_party p where p.code = 'AGENT-ACC'
on conflict (code) do nothing;
