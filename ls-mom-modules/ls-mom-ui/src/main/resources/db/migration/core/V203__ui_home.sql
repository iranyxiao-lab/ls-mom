-- modernize-ui-ux 3.9：首页卡片布局。角色默认布局存 meta_view（类型 HOME，编码 home.<角色键>，home.default 为通用默认）；
-- 个人调整存 ui_user_pref.home。以后再改视图类型约束时须保留全部已有类型。
alter table meta_view drop constraint if exists meta_view_view_type_check;
alter table meta_view add constraint meta_view_view_type_check
    check (view_type in ('FORM', 'LIST', 'DETAIL', 'PRINT', 'MOBILE', 'DASHBOARD', 'HOME'));

alter table ui_user_pref add column if not exists home jsonb;
comment on column ui_user_pref.home is '首页个人布局：{cards:[{code, hidden}]}，为空时用角色默认布局';

insert into meta_view (code, version, concept_code, view_type, display_name, status, schema_json, published_at, change_note)
values ('home.default', 1, null, 'HOME', '首页（通用默认）', 'PUBLISHED',
        '{"cards":[{"code":"ls.home.myReviews"},{"code":"ls.home.teamWork"},{"code":"ls.home.openIssues"},{"code":"ls.home.onTimeRate"},{"code":"ls.home.quickLinks"}]}',
        now(), '平台默认')
on conflict do nothing;

-- 管理员为角色设置首页默认布局的权限（挂在体验分析下）
insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
values (3703, '设置角色首页', 3700, 3, '#', '', '', '1', '0', 'F', '0', '0', 'lsmom:ui:home:manage', '#', 103, 1, now(), null, null, 'ls.ui')
on conflict (menu_id) do nothing;
