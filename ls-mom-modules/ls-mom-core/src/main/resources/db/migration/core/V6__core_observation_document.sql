-- 稳定核心：事实观察、附件关联与访问审计

create table core_observation
(
    id           bigserial primary key,
    object_type  varchar(64)  not null,
    object_id    varchar(64)  not null,
    item_code    varchar(64)  not null,
    value_num    numeric(20, 6),
    value_text   varchar(500),
    unit         varchar(32),
    observed_at  timestamp    not null,
    source       varchar(16)  not null check (source in ('MANUAL', 'DCS', 'EXTERNAL')),
    quality      varchar(8)   not null default 'GOOD' check (quality in ('GOOD', 'SUSPECT', 'BAD')),
    org_unit_id  bigint references core_org_unit (id),
    recorded_by  bigint references core_party (id),
    created_at   timestamp    not null default current_timestamp,
    check (value_num is not null or value_text is not null)
);
create index idx_core_observation_obj on core_observation (object_type, object_id, observed_at desc);
comment on table core_observation is '某时刻对某对象的事实观察（人工读数、DCS 快照、外部数据）；不存高频原始波形';

alter table core_party add column clearance varchar(16) not null default 'INTERNAL'
    check (clearance in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET'));
comment on column core_party.clearance is '可访问的最高附件密级';

create table core_document_link
(
    id             bigserial primary key,
    oss_id         bigint       not null,
    object_type    varchar(64)  not null,
    object_id      varchar(64)  not null,
    purpose        varchar(32)  not null default 'ATTACHMENT',
    classification varchar(16)  not null default 'INTERNAL'
        check (classification in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET')),
    doc_version    int          not null default 1,
    file_name      varchar(255) not null,
    org_unit_id    bigint references core_org_unit (id),
    created_by     bigint references core_party (id),
    created_at     timestamp    not null default current_timestamp,
    removed_at     timestamp,
    unique (object_type, object_id, oss_id)
);
create index idx_core_document_link_obj on core_document_link (object_type, object_id) where removed_at is null;
comment on table core_document_link is '对象存储文件与业务对象的关联；下载必须经受控下载校验数据域与密级';
comment on column core_document_link.oss_id is 'sys_oss.oss_id';
comment on column core_document_link.org_unit_id is '数据域：通常取所属业务对象的组织';

-- 访问审计：追加写，禁止修改与删除（包括管理员）
create table core_access_audit
(
    id           bigserial primary key,
    occurred_at  timestamp    not null default current_timestamp,
    actor_party  bigint,
    action       varchar(64)  not null,
    object_type  varchar(64)  not null,
    object_id    varchar(64)  not null,
    result       varchar(8)   not null check (result in ('ALLOWED', 'DENIED')),
    reason       varchar(500),
    trace_id     varchar(64)
);
create index idx_core_access_audit_obj on core_access_audit (object_type, object_id, occurred_at desc);
comment on table core_access_audit is '受控访问审计（附件下载等），只允许追加';

create or replace function core_forbid_audit_change() returns trigger as
$$
begin
    raise exception '审计记录只允许追加，不能修改或删除（表 %）', tg_table_name using errcode = 'insufficient_privilege';
end;
$$ language plpgsql;

create trigger trg_core_access_audit_append_only
    before update or delete on core_access_audit
    for each row execute function core_forbid_audit_change();
