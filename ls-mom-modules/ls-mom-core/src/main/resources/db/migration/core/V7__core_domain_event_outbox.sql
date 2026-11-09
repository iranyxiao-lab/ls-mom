-- 领域事件与 Outbox：与业务数据同事务写入，由投递 Worker 至少一次投递到各订阅方
-- 业务表保持当前事实；事件用于集成、审计和读模型，不用于重放重建状态。

create table core_domain_event
(
    event_id         uuid         primary key,
    type             varchar(160) not null,
    version          int          not null default 1,
    occurred_at      timestamp    not null default current_timestamp,
    actor_party      bigint,
    on_behalf_of     bigint,
    subject_concept  varchar(128) not null,
    subject_id       varchar(64)  not null,
    org_unit_id      bigint,
    trace_id         varchar(64),
    idempotency_key  varchar(200) not null,
    payload          jsonb        not null default '{}'::jsonb
);
create index idx_core_domain_event_subject on core_domain_event (subject_concept, subject_id, occurred_at);
create index idx_core_domain_event_type on core_domain_event (type, occurred_at);
comment on table core_domain_event is '领域事件：event_id、type、version、occurred_at、actor、subject、org_scope、trace_id、idempotency_key';

create table core_outbox
(
    id               bigserial    primary key,
    event_id         uuid         not null references core_domain_event (event_id),
    destination      varchar(64)  not null,
    status           varchar(12)  not null default 'PENDING' check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    attempts         int          not null default 0,
    next_attempt_at  timestamp    not null default current_timestamp,
    last_error       varchar(1000),
    created_at       timestamp    not null default current_timestamp,
    delivered_at     timestamp,
    unique (event_id, destination)
);
create index idx_core_outbox_due on core_outbox (next_attempt_at) where status = 'PENDING';
create index idx_core_outbox_dead on core_outbox (destination) where status = 'DEAD';
comment on table core_outbox is '事件投递队列；超过重试上限进入 DEAD（人工补偿列表），可手工重投';

-- 消费方幂等回执：同一消费方对同一幂等键只处理一次
create table core_consumer_receipt
(
    consumer         varchar(64)  not null,
    idempotency_key  varchar(255) not null,
    processed_at     timestamp    not null default current_timestamp,
    primary key (consumer, idempotency_key)
);

-- 已投递 Outbox 的归档（按保留策略迁出，不影响业务查询）
create table core_outbox_archive (like core_outbox including defaults);
