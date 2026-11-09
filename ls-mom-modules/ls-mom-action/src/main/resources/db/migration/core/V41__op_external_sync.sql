-- 外部写入（ERP/OA 过账与通知）的同步状态：外部成功前只记"待同步"，失败进入重试或人工补偿
create table op_external_sync
(
    id             bigserial    primary key,
    target_system  varchar(32)  not null,
    object_type    varchar(64)  not null,
    object_id      varchar(64)  not null,
    event_id       uuid         not null,
    status         varchar(12)  not null default 'PENDING' check (status in ('PENDING', 'SYNCED', 'FAILED')),
    attempts       int          not null default 0,
    external_ref   varchar(128),
    last_error     varchar(1000),
    created_at     timestamp    not null default current_timestamp,
    updated_at     timestamp    not null default current_timestamp,
    unique (target_system, event_id)
);
create index idx_op_external_sync_obj on op_external_sync (object_type, object_id);
comment on table op_external_sync is '外部系统同步状态（PENDING 待同步 / SYNCED 已同步 / FAILED 失败，重试中或待人工补偿）';
