-- 摘要：由 Agent 或人员根据上下文草拟，每个要点引用上下文中的记录；真人确认后发布。
-- 摘要只是对事实的说明，发布后不回写到被摘要的对象（原始事实仍以原记录为准）。
create table core_summary
(
    id               bigserial    primary key,
    kind             varchar(32)  not null,
    subject_concept  varchar(128) not null,
    subject_id       varchar(64)  not null,
    title            varchar(200) not null,
    content          text         not null,
    points           jsonb        not null default '[]',
    citations        jsonb        not null default '[]',
    status           varchar(16)  not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'DISCARDED')),
    org_unit_id      bigint references core_org_unit (id),
    source           varchar(16)  not null default 'HUMAN' check (source in ('HUMAN', 'AGENT')),
    model            varchar(120),
    created_by_party bigint,
    on_behalf_of     bigint,
    published_by     bigint,
    published_at     timestamp,
    discard_reason   varchar(500),
    created_at       timestamp    not null default current_timestamp,
    updated_at       timestamp    not null default current_timestamp,
    version          int          not null default 0
);
create index idx_core_summary_subject on core_summary (subject_concept, subject_id, created_at);
create index idx_core_summary_status on core_summary (status, org_unit_id);
comment on table core_summary is '摘要草稿与发布（带引用；人工确认后发布；不回写原始事实）';
comment on column core_summary.points is '要点：[{text, refs:[{type, id, label}]}]';
comment on column core_summary.citations is '全部引用（去重）：[{type, id, label}]';
comment on column core_summary.source is 'AGENT：由 Agent 草拟（created_by_party 为 Agent 主体，on_behalf_of 为代理的人）';
