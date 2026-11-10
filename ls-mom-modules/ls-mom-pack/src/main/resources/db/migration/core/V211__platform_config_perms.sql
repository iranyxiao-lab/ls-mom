-- 平台配置权限（应用中心、流程/表单/台账/指标/规则设计、变更评审、补偿重投）登记为可授予的按钮。
-- 这些页面是前端静态路由、按权限显示；此前权限码没有菜单项，只有超级管理员能用，角色管理里无法授予给实施顾问或管理员。
-- 目录本身隐藏（不出现在侧栏），只在角色授权树中出现。
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3800, '平台配置', 0, 90, 'lsmom-config', '', '', '1', '0', 'M', '1', '0', '', 'component', 103, 1, now(), null, null, 'ls.platform'),
(3801, '安装与升级应用', 3800, 1, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:pack:install', '#', 103, 1, now(), null, null, 'ls.platform'),
(3802, '评审变更', 3800, 2, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:changeset:review', '#', 103, 1, now(), null, null, 'ls.platform'),
(3803, '设计台账', 3800, 3, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:ledger:design', '#', 103, 1, now(), null, null, 'ls.platform'),
(3804, '设计表单', 3800, 4, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:form:design', '#', 103, 1, now(), null, null, 'ls.platform'),
(3805, '评审表单', 3800, 5, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:form:review', '#', 103, 1, now(), null, null, 'ls.platform'),
(3806, '发布表单', 3800, 6, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:form:publish', '#', 103, 1, now(), null, null, 'ls.platform'),
(3807, '设计流程', 3800, 7, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:flow:design', '#', 103, 1, now(), null, null, 'ls.platform'),
(3808, '发布流程', 3800, 8, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:flow:publish', '#', 103, 1, now(), null, null, 'ls.platform'),
(3809, '管理流程实例', 3800, 9, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:flow:admin', '#', 103, 1, now(), null, null, 'ls.platform'),
(3810, '设计指标', 3800, 10, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:metric:design', '#', 103, 1, now(), null, null, 'ls.platform'),
(3811, '查看规则', 3800, 11, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:rule:list', '#', 103, 1, now(), null, null, 'ls.platform'),
(3812, '编辑规则', 3800, 12, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:rule:edit', '#', 103, 1, now(), null, null, 'ls.platform'),
(3813, '发布规则', 3800, 13, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:rule:publish', '#', 103, 1, now(), null, null, 'ls.platform'),
(3814, '查看补偿记录', 3800, 14, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:compensation:list', '#', 103, 1, now(), null, null, 'ls.platform'),
(3815, '重投补偿', 3800, 15, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:compensation:redeliver', '#', 103, 1, now(), null, null, 'ls.platform')
on conflict (menu_id) do nothing;
