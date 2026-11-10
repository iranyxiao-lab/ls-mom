-- 前端体验设置（键值）与匿名使用埋点。埋点只存任务名、步骤名、事件类型和耗时：不存用户、不存表单值。
create table if not exists ui_setting
(
    setting_key   varchar(64) primary key,
    setting_value varchar(500) not null,
    updated_by    bigint,
    updated_at    timestamptz  not null default now()
);

insert into ui_setting (setting_key, setting_value) values ('usage.enabled', 'true') on conflict do nothing;

create table if not exists ui_usage_event
(
    id          bigserial primary key,
    occurred_at timestamptz not null default now(),
    task        varchar(64) not null,
    step        varchar(64),
    kind        varchar(16) not null check (kind in ('start', 'step', 'complete', 'abandon', 'error')),
    duration_ms integer check (duration_ms is null or duration_ms between 0 and 86400000)
);

create index if not exists idx_ui_usage_event_task on ui_usage_event (task, occurred_at desc);

comment on table ui_usage_event is '匿名使用埋点（无用户标识、无表单值），用于发现高成本流程与放弃点';

-- 菜单：系统监控 → 体验分析（放弃点报表、开关）
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values
(3700, '体验分析', 2, 20, 'ui-usage', 'lsmom/ui-usage/index', '', '1', '0', 'C', '0', '0', 'lsmom:ui:usage', 'chart', 103, 1, now(), null, null, 'ls.ui'),
(3701, '查看体验报表', 3700, 1, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:ui:usage', '#', 103, 1, now(), null, null, 'ls.ui'),
(3702, '开关使用埋点', 3700, 2, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:ui:usage:manage', '#', 103, 1, now(), null, null, 'ls.ui')
on conflict (menu_id) do nothing;
