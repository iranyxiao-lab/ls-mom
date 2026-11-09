-- Pack 生命周期：清单扩展、安装条目（漂移基线）、制品、ChangeSet 流水线阶段、复审通知

alter table meta_pack
    add column requester          varchar(100),
    add column owner_party        bigint references core_party (id),
    add column standard           boolean      not null default false,
    add column depends_on         jsonb        not null default '[]'::jsonb,
    add column artifact_sha       char(64),
    add column error              varchar(2000),
    add column review_status      varchar(16)  not null default 'OK' check (review_status in ('OK', 'REVIEW_DUE', 'SUGGEST_RETIRE')),
    add column review_checked_at  timestamp,
    add column review_notified_at timestamp,
    add column disabled_at        timestamp,
    add column previous_version   varchar(32);
comment on column meta_pack.depends_on is '依赖：[{code, range}]，停用与升级时检查';
comment on column meta_pack.review_status is '复审状态：到期为 REVIEW_DUE；到期且 90 天无使用为 SUGGEST_RETIRE';

create table meta_pack_item
(
    pack_code   varchar(64)  not null references meta_pack (code),
    kind        varchar(16)  not null check (kind in ('CONCEPT', 'STATE_MACHINE', 'RULE', 'VIEW', 'METRIC', 'FLOW', 'MIGRATION', 'SEED')),
    code        varchar(160) not null,
    version     int,
    path        varchar(300) not null,
    hash        char(64)     not null,
    source_hash char(64)     not null,
    definition  jsonb        not null,
    installed_at timestamp   not null default current_timestamp,
    primary key (pack_code, kind, code)
);
comment on table meta_pack_item is 'Pack 安装的每项定义及其规范化哈希：漂移检测的基线';
comment on column meta_pack_item.hash is '安装后运行库中定义的规范化哈希（漂移基线）';
comment on column meta_pack_item.source_hash is 'Pack 文件内容哈希：升级时文件未变的定义不重复发布';

create table pack_artifact
(
    sha256      char(64)     primary key,
    code        varchar(64)  not null,
    version     varchar(32)  not null,
    manifest    jsonb        not null,
    content     bytea        not null,
    uploaded_by bigint,
    uploaded_at timestamp    not null default current_timestamp
);
create index idx_pack_artifact_code on pack_artifact (code, uploaded_at desc);
comment on table pack_artifact is 'Pack 制品（zip，含 sha256 清单）：ChangeSet 引用、回滚与漂移回灌的来源';

alter table meta_change_set
    add column artifact_sha char(64) references pack_artifact (sha256),
    add column released_at  timestamp,
    add column released_by  bigint,
    add column review_note  varchar(1000);

create table meta_change_set_stage
(
    change_set_id bigint       not null references meta_change_set (id),
    stage         varchar(16)  not null check (stage in ('VALIDATE', 'SANDBOX', 'REGRESSION', 'PRIVILEGE', 'UPGRADE')),
    status        varchar(8)   not null check (status in ('PASSED', 'FAILED')),
    artifact_sha  char(64),
    detail        jsonb        not null default '{}'::jsonb,
    run_by        bigint,
    run_at        timestamp    not null default current_timestamp,
    primary key (change_set_id, stage)
);
comment on table meta_change_set_stage is 'ChangeSet 发布链路各阶段结果；发布前全部通过且经人工批准';

alter table biz_flow_notice drop constraint if exists biz_flow_notice_kind_check;
alter table biz_flow_notice add constraint biz_flow_notice_kind_check
    check (kind in ('REMIND', 'URGE', 'CC', 'ESCALATE', 'EXCEPTION', 'TRANSFER', 'PACK_REVIEW'));
