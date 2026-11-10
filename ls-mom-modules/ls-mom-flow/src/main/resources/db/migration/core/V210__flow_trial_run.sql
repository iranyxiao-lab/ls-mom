-- 设计器沙箱试运行轨迹：试运行本身在只回滚事务中执行，正式业务表、流程实例与待办不留记录；
-- 这里只保存给设计人员看的轨迹，可一键清除。
create table flow_trial_run (
    id          bigserial primary key,
    form_code   varchar(128) not null,
    flow_code   varchar(128),
    status      varchar(16)  not null check (status in ('PASSED', 'FAILED')),
    summary     varchar(500),
    trace       jsonb        not null,
    created_by  bigint       not null,
    created_at  timestamp    not null default current_timestamp
);
create index idx_flow_trial_run_form on flow_trial_run (form_code, created_by, id desc);
