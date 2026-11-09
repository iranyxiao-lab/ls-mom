-- 指标口径：规范化哈希用于重复口径检测；同一编码口径变化生成新版本，旧版本保留可查
alter table meta_metric add column if not exists definition_hash char(64);
alter table meta_metric add column if not exists concept_code varchar(128);
alter table meta_metric add column if not exists published_at timestamp;
alter table meta_metric add column if not exists created_by bigint;
create index if not exists idx_meta_metric_hash on meta_metric (definition_hash) where status = 'PUBLISHED';
comment on column meta_metric.definition_hash is '口径规范化哈希（来源、度量、过滤、时间字段），相同哈希视为重复口径';

-- 命名 Query：由研发在代码中定义（参数 Schema、数据域占位、超时、测试样例），启动时校验并登记
create table if not exists meta_named_query
(
    code          varchar(160) not null,
    version       int          not null,
    display_name  varchar(100) not null,
    description   varchar(1000),
    param_schema  jsonb        not null,
    timeout_ms    int          not null check (timeout_ms between 100 and 60000),
    permission    varchar(100),
    sql_hash      char(64)     not null,
    examples      jsonb        not null default '[]'::jsonb,
    status        varchar(16)  not null default 'PUBLISHED' check (status in ('PUBLISHED', 'RETIRED')),
    registered_at timestamp    not null default current_timestamp,
    primary key (code, version)
);
comment on table meta_named_query is '命名 Query 登记（SQL 只存在于代码中，此处存哈希与参数 Schema）';
