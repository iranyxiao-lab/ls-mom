-- 意见咨询 Pack 的菜单与权限点（页面、Operation 权限、通用台账权限）。菜单编号 3500–3599 保留给意见咨询。
-- 意见只能经 ls.consultation.Opinion.submit 提交，不开放通用台账新增 / 修改。
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3500, '意见咨询', 0, 25, 'consultation', null, '', '1', '0', 'M', '0', '0', '', 'message', 103, 1, now(), null, null, 'ls.consultation'),
(3501, '参与征求', 3500, 1, 'topics', 'lsmom/consultation/topics/index', '', '1', '0', 'C', '0', '0', 'ls:consultation:opine', 'edit', 103, 1, now(), null, null, 'ls.consultation'),
(3502, '意见处理', 3500, 2, 'handle', 'lsmom/consultation/handle/index', '', '1', '0', 'C', '0', '0', 'ls:consultation:reply', 'checkbox', 103, 1, now(), null, null, 'ls.consultation'),
(3503, '议题管理', 3500, 3, 'admin', 'lsmom/consultation/admin/index', '', '1', '0', 'C', '0', '0', 'ls:consultation:manage', 'list', 103, 1, now(), null, null, 'ls.consultation'),
(3510, '议题与总结维护', 3500, 10, '#', '', '', '1', '0', 'F', '0', '0', 'ls:consultation:manage', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3511, '答复意见', 3500, 11, '#', '', '', '1', '0', 'F', '0', '0', 'ls:consultation:reply', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3512, '提交意见', 3500, 12, '#', '', '', '1', '0', 'F', '0', '0', 'ls:consultation:opine', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3513, '查看匿名意见提交主体（审计）', 3500, 13, '#', '', '', '1', '0', 'F', '0', '0', 'ls:consultation:audit', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3520, '议题列表', 3500, 45, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Topic:list', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3521, '议题详情', 3500, 46, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Topic:query', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3522, '议题新增', 3500, 47, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Topic:add', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3523, '议题修改', 3500, 48, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Topic:edit', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3524, '议题删除', 3500, 49, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Topic:remove', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3525, '意见列表', 3500, 50, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Opinion:list', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3526, '意见详情', 3500, 51, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Opinion:query', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3527, '总结列表', 3500, 52, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Summary:list', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3528, '总结详情', 3500, 53, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Summary:query', '#', 103, 1, now(), null, null, 'ls.consultation'),
(3529, '总结修改', 3500, 54, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.consultation.Summary:edit', '#', 103, 1, now(), null, null, 'ls.consultation')
on conflict (menu_id) do nothing;
