-- 通用台账：DDL 迁移脚本（经 Flyway 以 pack_schema_history 执行并留痕）、导入批次、导出任务

create table meta_ddl_script
(
    id            bigserial primary key,
    concept_code  varchar(128) not null,
    pack_code     varchar(64),
    description   varchar(200) not null,
    script        text         not null,
    checksum      char(64)     not null,
    breaking      boolean      not null default false,
    change_set_id bigint references meta_change_set (id),
    status        varchar(16)  not null default 'PENDING' check (status in ('PENDING', 'APPLIED', 'FAILED')),
    error         varchar(2000),
    created_by    bigint,
    created_at    timestamp    not null default current_timestamp,
    applied_at    timestamp
);
comment on table meta_ddl_script is 'Concept 发布与经评审变更生成的 DDL 脚本；由 Pack 迁移执行器经 Flyway 执行（历史表 pack_schema_history），已执行脚本不可修改';

create table crud_import_batch
(
    id            bigserial primary key,
    concept_code  varchar(128) not null,
    file_name     varchar(255),
    total_rows    int          not null,
    valid_rows    int          not null,
    error_rows    int          not null,
    errors        jsonb        not null default '[]'::jsonb,
    payload       jsonb        not null,
    status        varchar(16)  not null default 'CHECKED' check (status in ('CHECKED', 'IMPORTED', 'EXPIRED')),
    imported_rows int,
    created_by    bigint,
    created_at    timestamp    not null default current_timestamp,
    imported_at   timestamp
);
comment on table crud_import_batch is 'Excel 导入预检结果：错误行报告与待导入的正确行，确认后入库';

create table crud_export_job
(
    id            bigserial primary key,
    concept_code  varchar(128) not null,
    query         jsonb        not null,
    status        varchar(16)  not null default 'PENDING' check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'REJECTED')),
    row_count     int,
    object_key    varchar(255),
    error         varchar(1000),
    created_by    bigint,
    created_at    timestamp    not null default current_timestamp,
    finished_at   timestamp
);
comment on table crud_export_job is '异步导出任务：受权限与条数上限约束，文件存对象存储，提交与下载均审计';
