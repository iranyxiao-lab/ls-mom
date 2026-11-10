-- 全局搜索读模型：由 Outbox 增量更新（工作、台账记录、人员）。只存编号、标题、副标题与拼音检索文本，
-- 以及数据域列（组织、创建人），查询时按调用者数据域过滤；功能权限按对象类型在查询前过滤。
create extension if not exists pg_trgm;

create table if not exists ui_search_index
(
    object_type      varchar(100) not null,
    object_id        varchar(64)  not null,
    code             varchar(100),
    title            varchar(300) not null,
    subtitle         varchar(300),
    org_unit_id      bigint,
    created_by_party bigint,
    search_text      text         not null,
    updated_at       timestamptz  not null default now(),
    primary key (object_type, object_id)
);

create index if not exists idx_ui_search_text on ui_search_index using gin (search_text gin_trgm_ops);
create index if not exists idx_ui_search_code on ui_search_index (lower(code));

comment on table ui_search_index is '全局搜索读模型（命名 Query ls.ui.globalSearch），由 SearchIndexer 经 Outbox 维护';
comment on column ui_search_index.search_text is '小写的编号、标题、标题全拼与首字母，供 pg_trgm 模糊匹配';
