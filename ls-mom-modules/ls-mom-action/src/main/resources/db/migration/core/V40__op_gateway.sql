-- Operation Gateway：幂等记录与执行审计

create table op_idempotency
(
    idempotency_key  varchar(200) primary key,
    op_code          varchar(160) not null,
    actor_party      bigint,
    input_hash       char(64)     not null,
    response         jsonb        not null,
    created_at       timestamp    not null default current_timestamp,
    expires_at       timestamp    not null
);
create index idx_op_idempotency_expires on op_idempotency (expires_at);
comment on table op_idempotency is 'Operation 幂等键：窗口内重复请求返回首次结果，不重复执行副作用';

create table op_audit
(
    id               bigserial    primary key,
    occurred_at      timestamp    not null default current_timestamp,
    op_code          varchar(160) not null,
    actor_type       varchar(8)   not null check (actor_type in ('HUMAN', 'AGENT', 'SERVICE', 'SYSTEM')),
    actor_user       bigint,
    actor_party      bigint,
    on_behalf_of     bigint,
    target_concept   varchar(128),
    target_id        varchar(64),
    data_version     int,
    result           varchar(10)  not null check (result in ('SUCCESS', 'FAILED', 'REPLAYED', 'SIMULATED')),
    error_code       varchar(64),
    error_message    varchar(1000),
    input_digest     char(64),
    input_summary    varchar(2000),
    duration_ms      int          not null,
    trace_id         varchar(64),
    idempotency_key  varchar(200)
);
create index idx_op_audit_target on op_audit (target_concept, target_id, occurred_at);
create index idx_op_audit_actor on op_audit (actor_party, occurred_at);
comment on table op_audit is 'Operation 执行审计（成功与失败），只允许追加';
comment on column op_audit.on_behalf_of is '代理主体：Agent 或服务代表该主体执行';

create trigger trg_op_audit_append_only
    before update or delete on op_audit
    for each row execute function core_forbid_audit_change();
