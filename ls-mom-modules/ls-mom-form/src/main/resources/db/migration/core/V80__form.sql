-- 表单：视图类型增加看板绑定；组件注册表（版本化、输入输出、权限、兼容版本、测试，不接受脚本）

alter table meta_view drop constraint if exists meta_view_view_type_check;
alter table meta_view add constraint meta_view_view_type_check
    check (view_type in ('FORM', 'LIST', 'DETAIL', 'PRINT', 'MOBILE', 'DASHBOARD'));
alter table meta_view alter column concept_code drop not null;
alter table meta_view add column created_by bigint;
alter table meta_view add column change_note varchar(500);
create index idx_meta_view_concept on meta_view (concept_code, view_type, status);
create unique index uq_meta_view_published on meta_view (code) where status = 'PUBLISHED';

create table form_component
(
    code           varchar(128) not null,
    version        varchar(32)  not null,
    display_name   varchar(100) not null,
    description    varchar(500),
    inputs         jsonb        not null default '[]'::jsonb,
    outputs        jsonb        not null default '[]'::jsonb,
    permission     varchar(128),
    compat_from    int          not null default 1,
    compat_to      int,
    test_ref       varchar(255) not null,
    status         varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'DEPRECATED')),
    registered_at  timestamp    not null default current_timestamp,
    primary key (code, version)
);
comment on table form_component is '表单自定义组件注册表：组件代码随 Pack 经评审发布，这里只登记契约；设计器只能从注册表选择，不接受粘贴代码';
comment on column form_component.compat_from is '兼容的 FormSchema 结构版本下限（含）';
comment on column form_component.test_ref is '组件测试位置（发布前必须存在）';
