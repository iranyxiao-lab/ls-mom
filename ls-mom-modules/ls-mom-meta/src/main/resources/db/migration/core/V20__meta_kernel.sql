-- 元数据内核：七类契约（Concept / View / Operation / Flow / Rule / Metric / Pack）与 ChangeSet
-- 迁移版本号分段：V1–V19 ls-mom-core，V20–V39 ls-mom-meta，V40–V59 ls-mom-action，V60–V79 ls-mom-crud，
-- V80–V99 ls-mom-form，V100–V119 ls-mom-flow，V120– 其余模块（见 docs/operation-gateway.md）。
-- 编码（code）一经发布不可修改、不可复用：删除只做状态标记，记录永久保留。

create table meta_concept
(
    code          varchar(128) primary key,
    namespace     varchar(64)  not null,
    display_name  varchar(100) not null,
    storage       varchar(16)  not null check (storage in ('CORE_TABLE', 'MANAGED_TABLE')),
    table_name    varchar(63)  not null unique,
    status        varchar(16)  not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    version       int          not null default 1,
    -- 只读：核心显式表与 Java 维护的标准对象不能经设计器修改结构
    editable      boolean      not null default true,
    owner_pack    varchar(64),
    description   varchar(1000),
    published_at  timestamp,
    created_at    timestamp    not null default current_timestamp,
    updated_at    timestamp    not null default current_timestamp,
    check (code like namespace || '.%')
);
comment on table meta_concept is 'Concept：业务对象语义注册（核心、标准 Pack、客户扩展）';
comment on column meta_concept.storage is 'CORE_TABLE 核心显式表；MANAGED_TABLE 由 DDL 生成器维护的受控物理表 c_<ns>_<concept>';

create table meta_concept_field
(
    concept_code  varchar(128) not null references meta_concept (code),
    field_code    varchar(64)  not null,
    display_name  varchar(100) not null,
    data_type     varchar(16)  not null check (data_type in ('STRING', 'TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE',
                                                          'DATETIME', 'ENUM', 'REF', 'JSON', 'FILE')),
    column_name   varchar(63)  not null,
    required      boolean      not null default false,
    ref_concept   varchar(128),
    options       jsonb,
    -- 状态字段：C1/C2 对象的状态字段禁止通用更新
    state_field   boolean      not null default false,
    system_field  boolean      not null default false,
    status        varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'DEPRECATED', 'REMOVED')),
    sort_order    int          not null default 0,
    since_version int          not null default 1,
    deprecated_at timestamp,
    removed_at    timestamp,
    primary key (concept_code, field_code),
    unique (concept_code, column_name)
);
comment on table meta_concept_field is 'Concept 字段；编码不可复用，删除先 DEPRECATED，确认无引用后才能经评审迁移 REMOVED';

create table meta_relation
(
    code          varchar(128) primary key,
    name          varchar(64)  not null,
    from_concept  varchar(128) not null references meta_concept (code),
    to_concept    varchar(128) not null references meta_concept (code),
    cardinality   varchar(8)   not null default 'MANY' check (cardinality in ('ONE', 'MANY')),
    via_field     varchar(64),
    display_name  varchar(100),
    created_at    timestamp    not null default current_timestamp
);
comment on table meta_relation is 'Concept 间的命名关系，如 assignedTo、observedOn、causedBy';

create table meta_mapping
(
    id            bigserial primary key,
    concept_code  varchar(128) not null references meta_concept (code),
    field_code    varchar(64),
    target_type   varchar(16)  not null check (target_type in ('TABLE', 'COLUMN', 'EXTERNAL', 'DCS_POINT')),
    target_ref    varchar(255) not null,
    system_code   varchar(64),
    unique (concept_code, field_code, target_type, target_ref)
);
comment on table meta_mapping is '语义到物理表/列、外部系统编码、DCS 点位的映射';

-- 字段使用方反查：发布表单、流程、指标、Operation 时登记
create table meta_field_usage
(
    concept_code  varchar(128) not null,
    field_code    varchar(64)  not null,
    user_type     varchar(16)  not null check (user_type in ('VIEW', 'FLOW', 'FLOW_NODE', 'METRIC', 'OPERATION', 'RULE')),
    user_code     varchar(255) not null,
    user_version  int          not null default 1,
    detail        varchar(255),
    primary key (concept_code, field_code, user_type, user_code, user_version)
);
create index idx_meta_field_usage_user on meta_field_usage (user_type, user_code);

create table meta_view
(
    code          varchar(128) not null,
    version       int          not null,
    concept_code  varchar(128) not null references meta_concept (code),
    view_type     varchar(16)  not null check (view_type in ('FORM', 'LIST', 'DETAIL', 'PRINT', 'MOBILE')),
    display_name  varchar(100) not null,
    status        varchar(16)  not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    schema_json   jsonb        not null,
    published_at  timestamp,
    created_at    timestamp    not null default current_timestamp,
    primary key (code, version)
);
comment on table meta_view is '表单/列表/详情/打印/移动视图（LS FormSchema），按版本冻结';

create table meta_operation
(
    code           varchar(160) primary key,
    concept_code   varchar(128) not null references meta_concept (code),
    display_name   varchar(100) not null,
    kind           varchar(16)  not null check (kind in ('HANDLER', 'TRANSITION', 'QUERY')),
    risk           varchar(4)   not null check (risk in ('C0', 'C1', 'C2')),
    exposure       varchar(20)  not null default 'NONE'
        check (exposure in ('NONE', 'QUERY', 'DRAFT', 'SIMULATE', 'APPROVAL_REQUIRED', 'DENY')),
    permission     varchar(128),
    input_schema   jsonb        not null default '{"type":"object"}'::jsonb,
    output_schema  jsonb,
    guards         jsonb        not null default '[]'::jsonb,
    idempotency    varchar(12)  not null default 'OPTIONAL' check (idempotency in ('NONE', 'OPTIONAL', 'REQUIRED')),
    audit_level    varchar(8)   not null default 'FULL' check (audit_level in ('BASIC', 'FULL')),
    requires_version boolean    not null default false,
    source         varchar(8)   not null check (source in ('CODE', 'META')),
    status         varchar(16)  not null default 'PUBLISHED' check (status in ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    version        int          not null default 1,
    description    varchar(1000),
    updated_at     timestamp    not null default current_timestamp
);
comment on table meta_operation is '命名 Operation 契约；未注册的动作不可调用';
comment on column meta_operation.source is 'CODE 由 @LsOperation 注解声明（启动时对账），META 由状态机迁移等元数据生成';

create table meta_flow
(
    code          varchar(128) not null,
    version       int          not null,
    concept_code  varchar(128) not null references meta_concept (code),
    kind          varchar(16)  not null check (kind in ('STATE_MACHINE', 'APPROVAL')),
    display_name  varchar(100) not null,
    status        varchar(16)  not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    definition    jsonb        not null,
    published_at  timestamp,
    created_at    timestamp    not null default current_timestamp,
    primary key (code, version)
);
comment on table meta_flow is '状态机（状态、迁移、守卫）与审批流定义，按版本冻结';

create table meta_rule
(
    code          varchar(160) not null,
    version       int          not null,
    concept_code  varchar(128) references meta_concept (code),
    level         varchar(4)   not null check (level in ('L0', 'L1', 'L2', 'L3', 'L4')),
    kind          varchar(16)  not null check (kind in ('CEL', 'DECISION_TABLE', 'JAVA', 'FIELD')),
    display_name  varchar(100) not null,
    message       varchar(500),
    definition    jsonb        not null,
    status        varchar(16)  not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    created_at    timestamp    not null default current_timestamp,
    primary key (code, version)
);
comment on table meta_rule is '约束规则：L0 提示 L1 字段 L2 跨字段 L3 跨对象 L4 人工审批';

create table meta_metric
(
    code          varchar(160) not null,
    version       int          not null,
    display_name  varchar(100) not null,
    definition    jsonb        not null,
    status        varchar(16)  not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'RETIRED')),
    created_at    timestamp    not null default current_timestamp,
    primary key (code, version)
);
comment on table meta_metric is '指标口径（来源、过滤、聚合、维度）';

create table meta_pack
(
    code          varchar(64)  primary key,
    version       varchar(32)  not null,
    display_name  varchar(100) not null,
    status        varchar(16)  not null check (status in ('INSTALLING', 'ENABLED', 'DISABLED', 'FAILED', 'UNINSTALLED')),
    owner         varchar(100),
    review_due    date,
    manifest      jsonb        not null,
    manifest_hash char(64)     not null,
    installed_at  timestamp,
    updated_at    timestamp    not null default current_timestamp
);
comment on table meta_pack is '已安装 Pack 及其清单（用于依赖、复审、漂移检测）';

create table meta_change_set
(
    id            bigserial primary key,
    pack_code     varchar(64),
    title         varchar(200) not null,
    status        varchar(16)  not null default 'DRAFT'
        check (status in ('DRAFT', 'REVIEWING', 'APPROVED', 'APPLIED', 'REJECTED')),
    breaking      boolean      not null default false,
    items         jsonb        not null default '[]'::jsonb,
    source        varchar(16)  not null default 'DESIGNER' check (source in ('DESIGNER', 'DRIFT', 'AI', 'PACK')),
    created_by    bigint,
    reviewed_by   bigint,
    created_at    timestamp    not null default current_timestamp,
    applied_at    timestamp
);
comment on table meta_change_set is '元数据变更集：设计器、漂移回灌、AI 生成的变更都经评审后应用';
