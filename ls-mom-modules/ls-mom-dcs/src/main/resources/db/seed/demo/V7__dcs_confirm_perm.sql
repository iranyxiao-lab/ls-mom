-- 设备员确认 DCS 报警生成的问题草稿（Issue 提交）需要登记异常权限 lsmom:issue:raise（菜单 3017）。仅在 lsmom.seed.demo.enabled=true 时执行。
insert into sys_role_menu (role_id, menu_id)
select 2104, 3017 where exists (select 1 from sys_role where role_id = 2104) and exists (select 1 from sys_menu where menu_id = 3017)
on conflict do nothing;
