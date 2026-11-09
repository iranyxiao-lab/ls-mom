-- 协同工作中心：委托（转办）记录；通知已读与待办完成相互独立
create table biz_task_transfer
(
    id          bigserial primary key,
    task_id     varchar(64)  not null,
    biz_type    varchar(128) not null,
    biz_id      varchar(64)  not null,
    node_name   varchar(100),
    from_party  bigint       not null,
    to_party    bigint       not null,
    reason      varchar(1000),
    created_at  timestamp    not null default current_timestamp
);
create index idx_biz_task_transfer_from on biz_task_transfer (from_party, created_at desc);
create index idx_biz_task_transfer_to on biz_task_transfer (to_party, created_at desc);
comment on table biz_task_transfer is '委托记录：谁把哪个待办转给了谁、原因（时间轴另有 ls.flow.task.transferred 事件）';
create index if not exists idx_biz_task_done on biz_task (completed_by, completed_at desc) where status = 'DONE';
create index if not exists idx_biz_flow_instance_initiator on biz_flow_instance (initiator_party, started_at desc);
