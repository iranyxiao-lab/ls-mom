-- 稳定核心：Issue 异常／缺陷／整改闭环外壳
-- 只含通用字段；缺陷、不符合项等专业字段在 Pack 扩展对象中（ext_concept + ext_id，一一关联）。

create sequence core_issue_no_seq;

create table core_issue
(
    id                bigserial primary key,
    code              varchar(32)  not null unique,
    issue_type        varchar(32)  not null,
    title             varchar(200) not null,
    description       text         not null default '',
    severity          varchar(8)   not null default 'MEDIUM' check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status            varchar(20)  not null default 'DRAFT'
        check (status in ('DRAFT', 'OPEN', 'IN_RECTIFICATION', 'PENDING_VERIFY', 'CLOSED', 'REJECTED', 'CANCELLED')),
    org_unit_id       bigint       not null references core_org_unit (id),
    constraint_profile varchar(64) not null references core_constraint_profile (code),
    source_type       varchar(32)  not null default 'MANUAL',
    -- 自动来源（如 DCS 报警）的去重键：同一键只产生一条 Issue
    source_key        varchar(200),
    occurrence_count  int          not null default 1,
    last_occurred_at  timestamp    not null default current_timestamp,
    reporter_party    bigint references core_party (id),
    owner_party       bigint references core_party (id),
    due_at            timestamp,
    verified_by       bigint references core_party (id),
    verify_conclusion varchar(1000),
    verified_at       timestamp,
    ext_concept       varchar(128),
    ext_id            bigint,
    created_at        timestamp    not null default current_timestamp,
    updated_at        timestamp    not null default current_timestamp,
    version           int          not null default 0
);
create unique index uk_core_issue_source_key on core_issue (source_key) where source_key is not null;
create unique index uk_core_issue_ext on core_issue (ext_concept, ext_id) where ext_id is not null;
create index idx_core_issue_org on core_issue (org_unit_id, status);
comment on table core_issue is '异常／问题／缺陷／整改闭环外壳';
comment on column core_issue.issue_type is '类型编码（ABNORMAL、DEFECT、NONCONFORMITY、SAFETY 等，可由 Pack 扩展）';
comment on column core_issue.source_key is '来源去重键；重复到达只累计发生次数';

-- 关联对象（任一关联都不是必须的）
create table core_issue_link
(
    id           bigserial primary key,
    issue_id     bigint       not null references core_issue (id),
    object_type  varchar(64)  not null,
    object_id    varchar(64)  not null,
    -- RECTIFICATION 表示由该 Issue 生成的整改工作
    link_role    varchar(16)  not null default 'RELATED' check (link_role in ('RELATED', 'SOURCE', 'RECTIFICATION', 'EVIDENCE')),
    created_at   timestamp    not null default current_timestamp,
    unique (issue_id, object_type, object_id, link_role)
);
create index idx_core_issue_link_obj on core_issue_link (object_type, object_id);
comment on table core_issue_link is 'Issue 与工作、设备、质量记录、产出、物料、观察或外部对象的关联';
