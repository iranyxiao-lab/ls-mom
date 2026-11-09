-- LS-MOM 演示种子：组织、角色、演示账号、班次与工作类型字典
-- 仅在 lsmom.seed.demo.enabled=true 时执行（本地 Compose 与验收环境），生产环境不执行。
-- 所有插入都带 on conflict do nothing，即使在已有数据的库上重复执行也不会产生重复数据。
-- 演示账号统一口令见 deploy/seed/README.md，仅限本地环境使用。

-- 组织：演示公司 → 车间 → 班组，以及设备科、质量科
insert into sys_dept (dept_id, tenant_id, parent_id, ancestors, dept_name, dept_category, order_num, leader, phone, email, status, del_flag, create_dept, create_by, create_time, update_by, update_time)
values (2000, '000000', 0,    '0',              'LS 演示工厂', 'COMPANY',  10, null, null, null, '0', '0', 103, 1, now(), null, null),
       (2010, '000000', 2000, '0,2000',         '一车间',      'WORKSHOP', 1,  null, null, null, '0', '0', 103, 1, now(), null, null),
       (2011, '000000', 2010, '0,2000,2010',    '一车间甲班',  'TEAM',     1,  null, null, null, '0', '0', 103, 1, now(), null, null),
       (2012, '000000', 2010, '0,2000,2010',    '一车间乙班',  'TEAM',     2,  null, null, null, '0', '0', 103, 1, now(), null, null),
       (2020, '000000', 2000, '0,2000',         '二车间',      'WORKSHOP', 2,  null, null, null, '0', '0', 103, 1, now(), null, null),
       (2021, '000000', 2020, '0,2000,2020',    '二车间丙班',  'TEAM',     1,  null, null, null, '0', '0', 103, 1, now(), null, null),
       (2030, '000000', 2000, '0,2000',         '设备科',      'DEPARTMENT', 3, null, null, null, '0', '0', 103, 1, now(), null, null),
       (2040, '000000', 2000, '0,2000',         '质量科',      'DEPARTMENT', 4, null, null, null, '0', '0', 103, 1, now(), null, null)
on conflict do nothing;

-- 角色（data_scope：4 本部门及以下，5 仅本人）
insert into sys_role (role_id, tenant_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, update_by, update_time, remark)
values (2101, '000000', '车间主任', 'workshop_leader',   10, '4', 't', 't', '0', '0', 103, 1, now(), null, null, 'LS 演示角色'),
       (2102, '000000', '班组长',   'team_leader',       11, '4', 't', 't', '0', '0', 103, 1, now(), null, null, 'LS 演示角色'),
       (2103, '000000', '操作工',   'operator',          12, '5', 't', 't', '0', '0', 103, 1, now(), null, null, 'LS 演示角色'),
       (2104, '000000', '设备员',   'asset_keeper',      13, '4', 't', 't', '0', '0', 103, 1, now(), null, null, 'LS 演示角色'),
       (2105, '000000', '质检员',   'quality_inspector', 14, '4', 't', 't', '0', '0', 103, 1, now(), null, null, 'LS 演示角色'),
       (2106, '000000', '实施顾问', 'implementer',       15, '1', 't', 't', '0', '0', 103, 1, now(), null, null, 'LS 演示角色')
on conflict do nothing;

-- 演示账号（统一口令，BCrypt）
insert into sys_user (user_id, tenant_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar, password, status, del_flag, login_ip, login_date, create_dept, create_by, create_time, update_by, update_time, remark)
values (2201, '000000', 2010, 'demo_leader',    '周主任（一车间）', 'sys_user', '', '', '0', null, '$2a$10$OqMJKcRdJt6U.Pi.BE/yseeeN4Z.8draHQHIrAzFBwVXoefarHesm', '0', '0', '', null, 103, 1, now(), null, null, 'LS 演示账号'),
       (2202, '000000', 2011, 'demo_team',      '吴班长（甲班）',   'sys_user', '', '', '0', null, '$2a$10$OqMJKcRdJt6U.Pi.BE/yseeeN4Z.8draHQHIrAzFBwVXoefarHesm', '0', '0', '', null, 103, 1, now(), null, null, 'LS 演示账号'),
       (2203, '000000', 2011, 'demo_operator',  '郑师傅（甲班）',   'sys_user', '', '', '0', null, '$2a$10$OqMJKcRdJt6U.Pi.BE/yseeeN4Z.8draHQHIrAzFBwVXoefarHesm', '0', '0', '', null, 103, 1, now(), null, null, 'LS 演示账号'),
       (2204, '000000', 2030, 'demo_asset',     '王工（设备科）',   'sys_user', '', '', '0', null, '$2a$10$OqMJKcRdJt6U.Pi.BE/yseeeN4Z.8draHQHIrAzFBwVXoefarHesm', '0', '0', '', null, 103, 1, now(), null, null, 'LS 演示账号'),
       (2205, '000000', 2040, 'demo_quality',   '冯工（质量科）',   'sys_user', '', '', '0', null, '$2a$10$OqMJKcRdJt6U.Pi.BE/yseeeN4Z.8draHQHIrAzFBwVXoefarHesm', '0', '0', '', null, 103, 1, now(), null, null, 'LS 演示账号'),
       (2206, '000000', 2000, 'demo_implementer','陈顾问（实施）',  'sys_user', '', '', '0', null, '$2a$10$OqMJKcRdJt6U.Pi.BE/yseeeN4Z.8draHQHIrAzFBwVXoefarHesm', '0', '0', '', null, 103, 1, now(), null, null, 'LS 演示账号')
on conflict do nothing;

insert into sys_user_role (user_id, role_id)
values (2201, 2101), (2202, 2102), (2203, 2103), (2204, 2104), (2205, 2105), (2206, 2106)
on conflict do nothing;

update sys_dept set leader = 2201 where dept_id = 2010 and leader is null;
update sys_dept set leader = 2202 where dept_id = 2011 and leader is null;

-- 字典：班次、工作类型
insert into sys_dict_type (dict_id, tenant_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark)
values (2301, '000000', '班次',     'ls_shift',     103, 1, now(), null, null, 'LS 班次'),
       (2302, '000000', '工作类型', 'ls_work_type', 103, 1, now(), null, null, 'LS WorkItem 工作类型')
on conflict do nothing;

insert into sys_dict_data (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, create_dept, create_by, create_time, update_by, update_time, remark)
values (2311, '000000', 1, '早班', 'DAY',     'ls_shift', '', 'primary', 'Y', 103, 1, now(), null, null, '08:00-16:00'),
       (2312, '000000', 2, '中班', 'EVENING', 'ls_shift', '', 'warning', 'N', 103, 1, now(), null, null, '16:00-24:00'),
       (2313, '000000', 3, '夜班', 'NIGHT',   'ls_shift', '', 'info',    'N', 103, 1, now(), null, null, '00:00-08:00'),
       (2321, '000000', 1, '生产', 'PRODUCTION',  'ls_work_type', '', 'primary', 'Y', 103, 1, now(), null, null, ''),
       (2322, '000000', 2, '维修', 'MAINTENANCE', 'ls_work_type', '', 'warning', 'N', 103, 1, now(), null, null, ''),
       (2323, '000000', 3, '点检', 'INSPECTION',  'ls_work_type', '', 'success', 'N', 103, 1, now(), null, null, ''),
       (2324, '000000', 4, '改善', 'IMPROVEMENT', 'ls_work_type', '', 'info',    'N', 103, 1, now(), null, null, ''),
       (2325, '000000', 5, '其他', 'OTHER',       'ls_work_type', '', 'default', 'N', 103, 1, now(), null, null, '')
on conflict do nothing;

-- 对象存储：指向本地 Compose 中的 S3 服务（参数来自部署环境变量）
update sys_oss_config
   set access_key = '${s3AccessKey}', secret_key = '${s3SecretKey}', bucket_name = '${s3Bucket}',
       endpoint = '${s3Endpoint}', is_https = 'N', status = '0'
 where config_key = 'minio';
