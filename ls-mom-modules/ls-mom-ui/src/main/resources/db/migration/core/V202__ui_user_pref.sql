-- modernize-ui-ux 3.6：用户偏好（主题、密度、布局、快捷键提示等）与收藏、最近访问，跨浏览器保持
create table if not exists ui_user_pref
(
    user_id    bigint primary key,
    prefs      jsonb     not null default '{}'::jsonb,
    nav        jsonb     not null default '{}'::jsonb,
    updated_at timestamp not null default current_timestamp
);
comment on table ui_user_pref is '用户界面偏好：prefs 为偏好项，nav 为收藏与最近访问';
