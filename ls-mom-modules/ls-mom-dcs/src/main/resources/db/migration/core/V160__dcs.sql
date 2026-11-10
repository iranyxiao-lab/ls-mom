-- DCS 集成（只读）：点位语义与映射、分钟级降采样快照、报警去重。高频原始值只在独立时序库（Historian），不进业务库。

create table dcs_point
(
    id             bigserial primary key,
    code           varchar(64)  not null unique,
    name           varchar(100) not null,
    unit           varchar(20),
    range_low      numeric(18, 6),
    range_high     numeric(18, 6),
    source_system  varchar(64)  not null,
    -- 映射：设备（ls.asset.Asset 的 id）、车间 / 组织、工艺段
    asset_id       bigint,
    org_unit_id    bigint,
    process_name   varchar(100),
    -- 报警处置策略：自动生成 Issue 草稿 / 仅提醒 / 忽略
    alarm_policy   varchar(16)  not null default 'NOTIFY' check (alarm_policy in ('AUTO_DRAFT', 'NOTIFY', 'IGNORE')),
    owner_party    bigint,
    enabled        boolean      not null default true,
    created_by     bigint,
    created_at     timestamp    not null default current_timestamp,
    updated_at     timestamp    not null default current_timestamp
);
create index idx_dcs_point_asset on dcs_point (asset_id);

-- 降采样快照：每点位每时间窗一行（默认 60 秒）；补传或重算时按主键覆盖，不产生重复
create table dcs_telemetry_snapshot
(
    point_code     varchar(64)    not null,
    bucket_start   timestamptz    not null,
    bucket_seconds integer        not null,
    avg_value      double precision,
    min_value      double precision,
    max_value      double precision,
    last_value     double precision,
    sample_count   integer        not null,
    bad_count      integer        not null default 0,
    skew_count     integer        not null default 0,
    computed_at    timestamp      not null default current_timestamp,
    primary key (point_code, bucket_seconds, bucket_start)
);

-- 业务报警：同一 dedup_key 只有一条，累加次数并取最大发生时间
create table dcs_alarm
(
    id                bigserial primary key,
    dedup_key         varchar(200) not null unique,
    source_system     varchar(64)  not null,
    point_code        varchar(64)  not null,
    alarm_type        varchar(32)  not null,
    severity          varchar(16),
    message           varchar(500),
    first_occurred_at timestamptz  not null,
    last_occurred_at  timestamptz  not null,
    first_received_at timestamptz  not null,
    last_received_at  timestamptz  not null,
    occurrence_count  integer      not null default 1,
    last_value        double precision,
    clock_anomaly     boolean      not null default false,
    trace_id          varchar(64),
    asset_id          bigint,
    org_unit_id       bigint,
    issue_id          bigint,
    suggested_issue_id bigint,
    status            varchar(16)  not null default 'NEW'
        check (status in ('NEW', 'DRAFTED', 'NOTIFIED', 'IGNORED', 'SUGGESTED', 'LINKED')),
    created_at        timestamp    not null default current_timestamp,
    updated_at        timestamp    not null default current_timestamp
);
create index idx_dcs_alarm_asset on dcs_alarm (asset_id, last_occurred_at desc);

-- 原始报警事件引用：同一来源事件只记一次（断网补传、重复送达去重）；坏质量位与时钟异常在此标记
create table dcs_alarm_event
(
    id              bigserial primary key,
    source_system   varchar(64)  not null,
    source_event_id varchar(128) not null,
    alarm_id        bigint references dcs_alarm (id),
    point_code      varchar(64)  not null,
    dedup_key       varchar(200) not null,
    occurred_at     timestamptz  not null,
    received_at     timestamptz  not null,
    quality         varchar(16)  not null,
    clock_anomaly   boolean      not null default false,
    value           double precision,
    trace_id        varchar(64),
    created_at      timestamp    not null default current_timestamp,
    unique (source_system, source_event_id)
);

-- 增量读取游标（按 Historian 的 received_at）
create table dcs_ingest_cursor
(
    name        varchar(100) primary key, -- 读取类型:Historian schema
    received_at timestamptz not null,
    updated_at  timestamp   not null default current_timestamp
);

alter table biz_flow_notice drop constraint if exists biz_flow_notice_kind_check;
alter table biz_flow_notice add constraint biz_flow_notice_kind_check
    check (kind in ('REMIND', 'URGE', 'CC', 'ESCALATE', 'EXCEPTION', 'TRANSFER', 'PACK_REVIEW', 'DCS_ALARM'));
