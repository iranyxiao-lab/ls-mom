-- 字段属性（可查询、可排序、索引、唯一、长度精度、字典、字段权限、是否进入 ext_attrs）与主子表关系
alter table meta_concept_field add column props jsonb not null default '{}'::jsonb;
comment on column meta_concept_field.props is
    '字段属性：queryable 可筛选、sortable 可排序、indexed 建索引、unique 唯一、maxLength、precision、scale、dictType 字典、readPermission/writePermission 字段权限、ext 非关键补充信息（存 ext_attrs，不建列）';
alter table meta_concept add column parent_concept varchar(128) references meta_concept (code);
comment on column meta_concept.parent_concept is '子表 Concept 所属的主表 Concept；子表行随主表单一事务保存';
