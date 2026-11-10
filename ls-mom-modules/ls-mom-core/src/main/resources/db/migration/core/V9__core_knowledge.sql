-- 知识库：文档按版本管理，只有已发布版本被分块入库（向量检索用 pgvector）；修订稿在发布前不可检索。
-- 分块冗余 org_unit_id 与 classification，检索 SQL 先做权限过滤再做相似度排序，返回前再按文档当前状态复核。
create extension if not exists vector;

create table core_knowledge_document
(
    id                bigserial    primary key,
    code              varchar(64)  not null unique,
    title             varchar(200) not null,
    category          varchar(32)  not null default 'SOP',
    org_unit_id       bigint references core_org_unit (id),
    classification    varchar(16)  not null default 'INTERNAL' check (classification in ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET')),
    published_version int,
    created_by        bigint,
    created_at        timestamp    not null default current_timestamp,
    updated_at        timestamp    not null default current_timestamp
);
comment on table core_knowledge_document is '知识库文档（org_unit_id 为空表示全公司可见；本组织及下级、上级组织的成员可见）';

create table core_knowledge_version
(
    id           bigserial   primary key,
    document_id  bigint      not null references core_knowledge_document (id),
    version      int         not null,
    content      text        not null,
    change_note  varchar(500),
    status       varchar(16) not null default 'DRAFT' check (status in ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    created_by   bigint,
    created_at   timestamp   not null default current_timestamp,
    published_by bigint,
    published_at timestamp,
    unique (document_id, version)
);
create unique index uq_core_knowledge_one_draft on core_knowledge_version (document_id) where status = 'DRAFT';

create table core_knowledge_chunk
(
    id             bigserial   primary key,
    document_id    bigint      not null references core_knowledge_document (id),
    version        int         not null,
    chunk_no       int         not null,
    org_unit_id    bigint,
    classification varchar(16) not null,
    heading        varchar(200),
    text           text        not null,
    embed_model    varchar(64) not null,
    embedding      vector      not null,
    created_at     timestamp   not null default current_timestamp
);
create index idx_core_knowledge_chunk_doc on core_knowledge_chunk (document_id, version);
create index idx_core_knowledge_chunk_scope on core_knowledge_chunk (embed_model, classification, org_unit_id);
comment on table core_knowledge_chunk is '已发布版本的分块与向量；发布新版本时整体替换';
