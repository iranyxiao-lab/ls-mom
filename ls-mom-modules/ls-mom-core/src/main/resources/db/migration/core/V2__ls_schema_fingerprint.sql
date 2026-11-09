-- 表结构指纹：每次迁移（核心或 Pack）完成后记录一次，启动时与实际结构比对，发现手工 DDL 漂移
create table ls_schema_fingerprint
(
    id          bigserial primary key,
    version     varchar(64)  not null,
    reason      varchar(255) not null,
    hash        char(64)     not null,
    snapshot    text         not null,
    recorded_at timestamp    not null default current_timestamp
);

comment on table ls_schema_fingerprint is '表结构指纹记录';
comment on column ls_schema_fingerprint.version is '记录时的迁移版本';
comment on column ls_schema_fingerprint.reason is '记录原因（core-migrate、pack-install 等）';
comment on column ls_schema_fingerprint.hash is 'snapshot 的 SHA-256';
comment on column ls_schema_fingerprint.snapshot is '规范化的表、列、索引清单（每行一项）';
