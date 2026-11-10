-- DCS 集成的菜单与权限点。菜单编号 3300–3399 保留给 DCS。全部为只读集成：没有任何写入 DCS 的权限点。
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3300, 'DCS', 0, 23, 'dcs', null, '', '1', '0', 'M', '0', '0', '', 'monitor', 103, 1, now(), null, null, 'ls.dcs'),
(3301, 'DCS 报警', 3300, 1, 'alarms', 'lsmom/dcs/alarms/index', '', '1', '0', 'C', '0', '0', 'ls:dcs:view', 'message', 103, 1, now(), null, null, 'ls.dcs'),
(3302, '测点', 3300, 2, 'points', 'lsmom/dcs/points/index', '', '1', '0', 'C', '0', '0', 'ls:dcs:view', 'chart', 103, 1, now(), null, null, 'ls.dcs'),
(3310, '测点维护', 3300, 10, '#', '', '', '1', '0', 'F', '0', '0', 'ls:dcs:manage', '#', 103, 1, now(), null, null, 'ls.dcs'),
(3311, '报警处置', 3300, 11, '#', '', '', '1', '0', 'F', '0', '0', 'ls:dcs:handle', '#', 103, 1, now(), null, null, 'ls.dcs')
on conflict (menu_id) do nothing;
