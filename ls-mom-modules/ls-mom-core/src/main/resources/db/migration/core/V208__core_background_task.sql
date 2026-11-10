create table core_background_task
(
    id          bigserial primary key,
    kind        varchar(32)  not null,
    title       varchar(200) not null,
    status      varchar(16)  not null default 'RUNNING' check (status in ('RUNNING', 'DONE', 'FAILED')),
    total       int          not null default 0,
    done        int          not null default 0,
    failed      int          not null default 0,
    result      jsonb,
    error       varchar(1000),
    created_by  bigint       not null,
    created_at  timestamp    not null default current_timestamp,
    updated_at  timestamp    not null default current_timestamp,
    finished_at timestamp,
    notified_at timestamp
);
create index idx_core_background_task_user on core_background_task (created_by, created_at desc);
comment on table core_background_task is '任务中心：导入、导出、批量操作、Pack 安装等耗时任务的进度与结果；完成后通知发起人';
comment on column core_background_task.notified_at is '发起人已收到完成通知的时间（前端确认后写入）';
