-- 流程协同：流程实例绑定、待办读模型、回调日志、通知、批准证据。
-- Flowable 引擎表在独立 schema flowable 中，由引擎自建；这里只放 LS 读模型与业务绑定。

create table biz_flow_instance
(
    id              bigserial primary key,
    flow_code       varchar(128) not null,
    flow_version    int          not null,
    form_code       varchar(128),
    form_version    int,
    biz_type        varchar(128) not null,
    biz_id          varchar(64)  not null,
    proc_inst_id    varchar(64) unique,
    proc_def_id     varchar(128),
    initiator_party bigint,
    org_unit_id     bigint,
    status          varchar(16)  not null default 'RUNNING'
        check (status in ('RUNNING', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELED')),
    sync_status     varchar(16)  not null default 'SYNCED'
        check (sync_status in ('PENDING_START', 'SYNCED', 'PENDING_RETRY', 'FAILED')),
    attempts        int          not null default 0,
    last_error      varchar(1000),
    next_retry_at   timestamp,
    trace_id        varchar(64),
    started_at      timestamp    not null default current_timestamp,
    ended_at        timestamp,
    updated_at      timestamp    not null default current_timestamp
);
create index idx_biz_flow_instance_biz on biz_flow_instance (biz_type, biz_id);
create index idx_biz_flow_instance_sync on biz_flow_instance (sync_status) where sync_status <> 'SYNCED';
comment on table biz_flow_instance is '流程实例与业务对象绑定：冻结流程版本与表单版本；同步状态用于启动失败重试';

create table biz_task
(
    id               bigserial primary key,
    task_id          varchar(64) unique,
    kind             varchar(16)  not null default 'TASK' check (kind in ('TASK', 'EXCEPTION', 'CC', 'ESCALATION')),
    flow_instance_id bigint references biz_flow_instance (id),
    proc_inst_id     varchar(64),
    biz_type         varchar(128) not null,
    biz_id           varchar(64)  not null,
    node_key         varchar(64)  not null,
    node_name        varchar(100),
    assignee_party   bigint,
    candidates       jsonb        not null default '[]',
    due_at           timestamp,
    status           varchar(16)  not null default 'TODO' check (status in ('TODO', 'DONE', 'CANCELED')),
    outcome          varchar(16),
    comment          varchar(1000),
    completed_by     bigint,
    completed_at     timestamp,
    exception_reason varchar(500),
    org_unit_id      bigint,
    flow_version     int,
    form_version     int,
    trace_id         varchar(64),
    last_urged_at    timestamp,
    created_at       timestamp    not null default current_timestamp,
    updated_at       timestamp    not null default current_timestamp,
    version          int          not null default 0
);
create index idx_biz_task_todo on biz_task (status, assignee_party);
create index idx_biz_task_candidates on biz_task using gin (candidates);
create index idx_biz_task_proc on biz_task (proc_inst_id);
create index idx_biz_task_biz on biz_task (biz_type, biz_id);
comment on table biz_task is '待办读模型：由引擎全局事件监听器在同一事务内维护；对账作业可按引擎运行时重建';
comment on column biz_task.kind is 'TASK 引擎任务；EXCEPTION 无人可办的异常待办；CC 抄送；ESCALATION 超时升级知会';

create table biz_flow_callback_log
(
    id              bigserial primary key,
    idempotency_key varchar(200) not null unique,
    proc_inst_id    varchar(64)  not null,
    task_id         varchar(64),
    event           varchar(32)  not null,
    operation       varchar(160) not null,
    biz_type        varchar(128) not null,
    biz_id          varchar(64)  not null,
    on_behalf_of    bigint,
    comment         varchar(1000),
    status          varchar(16)  not null default 'PENDING' check (status in ('PENDING', 'DONE', 'PENDING_RETRY', 'FAILED')),
    attempts        int          not null default 0,
    last_error      varchar(1000),
    next_retry_at   timestamp,
    created_at      timestamp    not null default current_timestamp,
    done_at         timestamp
);
create index idx_biz_flow_callback_pending on biz_flow_callback_log (status, next_retry_at) where status in ('PENDING', 'PENDING_RETRY');
comment on table biz_flow_callback_log is '流程结果 → 命名 Operation 回调；幂等键 procInstId:taskId:event，失败重试';
comment on column biz_flow_callback_log.on_behalf_of is '最后办理人：回调以流程引擎身份代表其执行';
comment on column biz_flow_callback_log.comment is '最后办理意见：回调 Operation 输入声明 conclusion/reason/comment 时填入';

create table biz_flow_notice
(
    id           bigserial primary key,
    kind         varchar(16)  not null check (kind in ('REMIND', 'URGE', 'CC', 'ESCALATE', 'EXCEPTION', 'TRANSFER')),
    party_id     bigint       not null,
    task_id      varchar(64),
    proc_inst_id varchar(64),
    biz_type     varchar(128),
    biz_id       varchar(64),
    message      varchar(500) not null,
    created_by   bigint,
    created_at   timestamp    not null default current_timestamp,
    read_at      timestamp
);
create index idx_biz_flow_notice_party on biz_flow_notice (party_id, read_at);
comment on table biz_flow_notice is '流程通知：提醒、催办、抄送、超时升级、异常待办、转办';

create table biz_approval_evidence
(
    id           bigserial primary key,
    biz_type     varchar(128) not null,
    biz_id       varchar(64)  not null,
    proc_inst_id varchar(64)  not null,
    flow_code    varchar(128) not null,
    flow_version int          not null,
    approvers    jsonb        not null,
    approved_at  timestamp    not null,
    input_digest varchar(64)  not null,
    data_version int          not null,
    data_digest  varchar(64)  not null,
    created_at   timestamp    not null default current_timestamp
);
create index idx_biz_approval_evidence_biz on biz_approval_evidence (biz_type, biz_id, id desc);
comment on table biz_approval_evidence is '受控审批的批准证据：批准人、时间、输入摘要、数据版本与数据摘要；业务数据实质变化后失效';

-- 审批流发布产物：部署 ID、流程定义 ID 与生成的 BPMN（只读，供预览与对账）
alter table meta_flow add column deployment_id varchar(64);
alter table meta_flow add column proc_def_id varchar(128);
alter table meta_flow add column bpmn_xml text;
alter table meta_flow add column created_by bigint;
