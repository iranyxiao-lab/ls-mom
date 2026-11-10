-- 知识库菜单与权限点：问答（含指标问数）、维护修订稿、发布（须真人）。
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3604, '知识问答', 3600, 4, 'knowledge', 'lsmom/agent/knowledge/index', '', '1', '0', 'C', '0', '0', 'lsmom:knowledge:ask', 'education', 103, 1, now(), null, null, 'ls.agent'),
(3613, '知识问答', 3604, 1, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:knowledge:ask', '#', 103, 1, now(), null, null, 'ls.agent'),
(3614, '维护知识库文档', 3604, 2, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:knowledge:manage', '#', 103, 1, now(), null, null, 'ls.agent'),
(3615, '发布知识库文档', 3604, 3, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:knowledge:publish', '#', 103, 1, now(), null, null, 'ls.agent')
on conflict (menu_id) do nothing;
