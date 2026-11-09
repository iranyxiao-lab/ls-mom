-- 稳定核心：工作协同（WorkItem / Assignment / Report / Relation）与约束 Profile
-- 核心对象不含任何 BOM、工艺路线、库存、批次、成本字段；专业字段由 Pack 扩展对象承载（ext_concept + ext_id）。

create table core_constraint_profile
(
    code        varchar(64)  primary key,
    name        varchar(100) not null,
    -- 资源时间冲突：WARN 提示 / BLOCK 阻断
    conflict_mode         varchar(8) not null default 'WARN' check (conflict_mode in ('WARN', 'BLOCK')),
    output_unit_required  boolean    not null default false,
    material_required     boolean    not null default false,
    -- Issue 关闭时验证人不得是唯一整改执行人
    verify_separation     boolean    not null default false,
    builtin     boolean      not null default false,
    created_at  timestamp    not null default current_timestamp,
    updated_at  timestamp    not null default current_timestamp
);
comment on table core_constraint_profile is '约束 Profile：可选增强约束，切换不影响历史已完成记录';

insert into core_constraint_profile (code, name, conflict_mode, output_unit_required, material_required, verify_separation, builtin)
values ('LOOSE', '宽松（默认）', 'WARN', false, false, false, true),
       ('STRICT', '严格', 'BLOCK', true, true, true, true);

-- 组织启用的 Profile；工作按所属组织向上查找最近的设置
alter table core_org_unit add column constraint_profile varchar(64) references core_constraint_profile (code);
comment on column core_org_unit.constraint_profile is '本组织及下级默认使用的约束 Profile（为空则继承上级，顶层默认 LOOSE）';

create sequence core_work_item_no_seq;

create table core_work_item
(
    id                 bigserial primary key,
    code               varchar(32)  not null unique,
    work_type          varchar(16)  not null check (work_type in ('PRODUCTION', 'MAINTENANCE', 'INSPECTION', 'IMPROVEMENT', 'OTHER')),
    title              varchar(200) not null,
    description        text         not null default '',
    org_unit_id        bigint       not null references core_org_unit (id),
    constraint_profile varchar(64)  not null references core_constraint_profile (code),
    status             varchar(20)  not null default 'DRAFT'
        check (status in ('DRAFT', 'READY', 'ASSIGNED', 'IN_PROGRESS', 'PENDING_CONFIRM', 'DONE', 'BLOCKED', 'CANCELLED')),
    priority           smallint     not null default 3 check (priority between 1 and 5),
    planned_start      timestamp,
    planned_end        timestamp,
    actual_start       timestamp,
    actual_end         timestamp,
    progress           numeric(5, 2) not null default 0,
    block_reason       varchar(500),
    -- 运行时定义版本冻结
    form_code          varchar(100),
    form_version       int,
    flow_version       int,
    schema_version     int,
    -- Pack 扩展对象
    ext_concept        varchar(128),
    ext_id             bigint,
    -- 来源（如 Issue 整改、设备缺陷）
    source_type        varchar(64),
    source_id          bigint,
    created_by_party   bigint references core_party (id),
    created_at         timestamp    not null default current_timestamp,
    updated_at         timestamp    not null default current_timestamp,
    version            int          not null default 0,
    check (planned_end is null or planned_start is null or planned_end >= planned_start)
);
create index idx_core_work_item_org on core_work_item (org_unit_id, status);
create index idx_core_work_item_source on core_work_item (source_type, source_id);
comment on table core_work_item is '工作项：生产、维修、点检、改善与临时工作的统一闭环对象';
comment on column core_work_item.status is '只能经命名 Operation 按状态机迁移';

create table core_work_assignment
(
    id             bigserial primary key,
    work_item_id   bigint      not null references core_work_item (id),
    resource_type  varchar(16) not null check (resource_type in ('PARTY', 'TEAM', 'SITE', 'EQUIPMENT')),
    resource_ref   varchar(64) not null,
    role           varchar(16) not null default 'RESPONSIBLE' check (role in ('RESPONSIBLE', 'ASSIST', 'REVIEW', 'CC')),
    planned_start  timestamp,
    planned_end    timestamp,
    status         varchar(16) not null default 'ACTIVE' check (status in ('ACTIVE', 'RELEASED')),
    assigned_by    bigint references core_party (id),
    created_at     timestamp   not null default current_timestamp
);
create index idx_core_work_assignment_res on core_work_assignment (resource_type, resource_ref) where status = 'ACTIVE';
create index idx_core_work_assignment_work on core_work_assignment (work_item_id);
comment on table core_work_assignment is '工作分派：人员、班组、场地、设备的任意组合';

create table core_work_report
(
    id             bigserial primary key,
    work_item_id   bigint       not null references core_work_item (id),
    reporter_party bigint       references core_party (id),
    status         varchar(16)  not null default 'DRAFT' check (status in ('DRAFT', 'SUBMITTED')),
    progress       numeric(5, 2) check (progress between 0 and 100),
    labor_minutes  int check (labor_minutes >= 0),
    output_qty     numeric(18, 4),
    output_unit    varchar(32),
    consumption    jsonb        not null default '[]'::jsonb,
    remark         varchar(2000),
    submitted_at   timestamp,
    created_at     timestamp    not null default current_timestamp,
    updated_at     timestamp    not null default current_timestamp,
    version        int          not null default 0
);
create index idx_core_work_report_work on core_work_report (work_item_id, status);
comment on table core_work_report is '工作填报：进度、人工、产出、消耗、说明；不产生库存或会计过账';
comment on column core_work_report.consumption is '物料消耗记录 [{name, qty, unit}]，仅记录事实';

create table core_work_relation
(
    id            bigserial primary key,
    from_id       bigint      not null references core_work_item (id),
    to_id         bigint      not null references core_work_item (id),
    relation_type varchar(16) not null check (relation_type in ('PARENT', 'DEPENDS', 'RELATED', 'BLOCKS')),
    created_at    timestamp   not null default current_timestamp,
    unique (from_id, to_id, relation_type),
    check (from_id <> to_id)
);
create index idx_core_work_relation_to on core_work_relation (to_id, relation_type);
comment on table core_work_relation is '工作关系；PARENT 表示 from 是 to 的父工作，不允许成环';
