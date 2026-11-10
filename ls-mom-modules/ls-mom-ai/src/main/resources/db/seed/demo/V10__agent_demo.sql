-- 演示：Agent 菜单授权与一个交接班助手服务身份（API Key 须在「Agent 管理」页签发，明文只显示一次）。
-- 实施顾问管理 Agent；车间主任、班组长、实施顾问处理 Agent 的审批申请。仅在 lsmom.seed.demo.enabled=true 时执行。
insert into sys_role_menu (role_id, menu_id)
select r, m from (values
(2106, 3600), (2106, 3601), (2106, 3602), (2106, 3610),
(2101, 3600), (2101, 3602),
(2102, 3600), (2102, 3602)
) v(r, m)
where exists (select 1 from sys_role where role_id = r)
on conflict do nothing;

insert into core_party (code, party_type, name)
values ('AGENT-SHIFT-ASSISTANT', 'AGENT', '交接班助手')
on conflict (code) do nothing;

insert into agent_identity (code, name, party_id, permissions, rate_limit_per_minute, description)
select 'shift-assistant', '交接班助手', p.id,
       '["lsmom:work:execute","lsmom:issue:raise","lsmom:issue:handle","lsmom:work:edit"]'::jsonb, 60,
       '读取工作、登记异常草稿、模拟关闭前检查、经批准生成整改工作'
  from core_party p where p.code = 'AGENT-SHIFT-ASSISTANT'
on conflict (code) do nothing;
