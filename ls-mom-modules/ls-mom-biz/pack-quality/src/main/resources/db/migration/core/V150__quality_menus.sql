-- 质量 Pack 的菜单与权限点（页面、Operation 权限、通用台账权限）。Pack 元数据由标准 Pack 安装器在启动时安装。
-- 菜单编号 3200–3299 保留给质量。
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3200, '质量', 0, 22, 'quality', null, '', '1', '0', 'M', '0', '0', '', 'validCode', 103, 1, now(), null, null, 'ls.quality'),
(3201, '质量记录', 3200, 1, 'records', 'lsmom/quality/records/index', '', '1', '0', 'C', '0', '0', 'ls:quality:view:records', 'documentation', 103, 1, now(), null, null, 'ls.quality'),
(3202, '不符合项', 3200, 2, 'nc', 'lsmom/quality/nc/index', '', '1', '0', 'C', '0', '0', 'ls:quality:view:nc', 'bug', 103, 1, now(), null, null, 'ls.quality'),
(3210, '登记质量记录', 3200, 10, '#', '', '', '1', '0', 'F', '0', '0', 'ls:quality:record', '#', 103, 1, now(), null, null, 'ls.quality'),
(3211, '登记不符合项', 3200, 11, '#', '', '', '1', '0', 'F', '0', '0', 'ls:quality:report', '#', 103, 1, now(), null, null, 'ls.quality'),
(3212, '车间审核', 3200, 12, '#', '', '', '1', '0', 'F', '0', '0', 'ls:quality:audit', '#', 103, 1, now(), null, null, 'ls.quality'),
(3213, '整改', 3200, 13, '#', '', '', '1', '0', 'F', '0', '0', 'ls:quality:rectify', '#', 103, 1, now(), null, null, 'ls.quality'),
(3214, '质量终判', 3200, 14, '#', '', '', '1', '0', 'F', '0', '0', 'ls:quality:verify', '#', 103, 1, now(), null, null, 'ls.quality'),
(3215, '工作执行与填报', 3200, 15, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:work:execute', '#', 103, 1, now(), null, null, 'ls.quality'),
(3216, '工作确认', 3200, 16, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:work:confirm', '#', 103, 1, now(), null, null, 'ls.quality'),
(3230, '质量记录列表', 3200, 30, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.QualityRecord:list', '#', 103, 1, now(), null, null, 'ls.quality'),
(3231, '质量记录详情', 3200, 31, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.QualityRecord:query', '#', 103, 1, now(), null, null, 'ls.quality'),
(3232, '质量记录新增', 3200, 32, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.QualityRecord:add', '#', 103, 1, now(), null, null, 'ls.quality'),
(3233, '质量记录修改', 3200, 33, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.QualityRecord:edit', '#', 103, 1, now(), null, null, 'ls.quality'),
(3234, '质量记录删除', 3200, 34, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.QualityRecord:remove', '#', 103, 1, now(), null, null, 'ls.quality'),
(3235, '不符合项列表', 3200, 35, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.NonConformance:list', '#', 103, 1, now(), null, null, 'ls.quality'),
(3236, '不符合项详情', 3200, 36, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.NonConformance:query', '#', 103, 1, now(), null, null, 'ls.quality'),
(3237, '不符合项新增', 3200, 37, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.NonConformance:add', '#', 103, 1, now(), null, null, 'ls.quality'),
(3238, '不符合项修改', 3200, 38, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.NonConformance:edit', '#', 103, 1, now(), null, null, 'ls.quality'),
(3239, '不符合项删除', 3200, 39, '#', '', '', '1', '0', 'F', '0', '0', 'crud:ls.quality.NonConformance:remove', '#', 103, 1, now(), null, null, 'ls.quality')
on conflict (menu_id) do nothing;
