-- 演示角色上传现场照片、交接班附件需要文件上传权限（system:oss:upload，菜单 1601）。
-- 只授按钮权限，不授"文件管理"菜单本身（角色为菜单严格关联，侧边栏不会出现文件管理）。
insert into sys_role_menu (role_id, menu_id)
select r, 1601 from (values (2101), (2102), (2103), (2106)) as g(r)
where exists (select 1 from sys_role where role_id = g.r) and exists (select 1 from sys_menu where menu_id = 1601)
on conflict do nothing;
