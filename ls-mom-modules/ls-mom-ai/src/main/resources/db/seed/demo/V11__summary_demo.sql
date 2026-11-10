-- 演示：车间主任、班组长、实施顾问可草拟并确认摘要；交接班助手只能草拟（发布永不暴露给 Agent）。
insert into sys_role_menu (role_id, menu_id)
select r, m from (values
(2101, 3603), (2101, 3611), (2101, 3612),
(2102, 3603), (2102, 3611), (2102, 3612),
(2106, 3603), (2106, 3611), (2106, 3612)
) v(r, m)
where exists (select 1 from sys_role where role_id = r)
on conflict do nothing;

update agent_identity set permissions = permissions || '["lsmom:summary:draft"]'::jsonb
 where code = 'shift-assistant' and not permissions @> '["lsmom:summary:draft"]'::jsonb;
