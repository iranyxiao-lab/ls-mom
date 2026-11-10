-- 运行期 Agent 的菜单与权限点。菜单编号 3600–3699 保留给 Agent。
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3600, 'Agent', 0, 26, 'agent', null, '', '1', '0', 'M', '0', '0', '', 'skill', 103, 1, now(), null, null, 'ls.agent'),
(3601, 'Agent 管理', 3600, 1, 'admin', 'lsmom/agent/admin/index', '', '1', '0', 'C', '0', '0', 'lsmom:agent:manage', 'peoples', 103, 1, now(), null, null, 'ls.agent'),
(3602, 'Agent 审批', 3600, 2, 'approvals', 'lsmom/agent/approvals/index', '', '1', '0', 'C', '0', '0', '', 'checkbox', 103, 1, now(), null, null, 'ls.agent'),
(3610, 'Agent 身份与密钥管理', 3601, 10, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:agent:manage', '#', 103, 1, now(), null, null, 'ls.agent')
on conflict (menu_id) do nothing;
