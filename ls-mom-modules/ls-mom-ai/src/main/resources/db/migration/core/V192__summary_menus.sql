-- 摘要确认：Agent 或人员草拟的带引用摘要，由有确认权限的真人发布或作废。
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3603, '摘要确认', 3600, 3, 'summaries', 'lsmom/agent/summaries/index', '', '1', '0', 'C', '0', '0', 'lsmom:summary:confirm', 'documentation', 103, 1, now(), null, null, 'ls.agent'),
(3611, '草拟摘要', 3603, 1, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:summary:draft', '#', 103, 1, now(), null, null, 'ls.agent'),
(3612, '确认发布摘要', 3603, 2, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:summary:confirm', '#', 103, 1, now(), null, null, 'ls.agent')
on conflict (menu_id) do nothing;
