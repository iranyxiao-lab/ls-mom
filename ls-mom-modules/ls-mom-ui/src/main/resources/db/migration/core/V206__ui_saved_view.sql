-- modernize-ui-ux 5.2：列表保存视图（筛选＋列＋排序＋分组）。个人视图只有本人可见；共享给角色的视图该角色的用户都能选用。
create table if not exists ui_saved_view (
    id            bigserial primary key,
    concept_code  varchar(128) not null,
    name          varchar(64)  not null,
    owner_user_id bigint       not null,
    shared_role   varchar(64),
    definition    jsonb        not null,
    created_at    timestamp    not null default now(),
    updated_at    timestamp
);
create index if not exists ui_saved_view_owner_idx on ui_saved_view (concept_code, owner_user_id);
create index if not exists ui_saved_view_role_idx on ui_saved_view (concept_code, shared_role) where shared_role is not null;
comment on table ui_saved_view is '列表保存视图：definition = {filters:[{field,op,value}], columns:[{code,visible,width,fixed}], sort:[{field,dir}], groupBy}';

-- 每个用户每个台账的默认视图（打开列表时自动应用）
create table if not exists ui_saved_view_default (
    user_id      bigint       not null,
    concept_code varchar(128) not null,
    view_id      bigint       not null references ui_saved_view (id) on delete cascade,
    primary key (user_id, concept_code)
);

-- 把视图共享给角色的权限（挂在体验分析下）
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values (3704, '共享列表视图', 3700, 4, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:ui:view:share', '#', 103, 1, now(), null, null, 'ls.ui')
on conflict (menu_id) do nothing;
