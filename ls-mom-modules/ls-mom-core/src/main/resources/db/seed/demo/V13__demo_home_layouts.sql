-- 演示：各角色首页默认卡片布局（modernize-ui-ux 3.9；视图类型 HOME 由 ls-mom-ui 迁移 V203 增加）
insert into meta_view (code, version, concept_code, view_type, display_name, status, schema_json, published_at, change_note)
values
('home.team_leader', 1, null, 'HOME', '首页（班组长）', 'PUBLISHED',
 '{"cards":[{"code":"ls.home.teamWork"},{"code":"ls.home.myReviews"},{"code":"ls.home.openIssues"},{"code":"ls.home.quickLinks"},{"code":"ls.home.onTimeRate"}]}', now(), '演示数据'),
('home.workshop_leader', 1, null, 'HOME', '首页（车间主任）', 'PUBLISHED',
 '{"cards":[{"code":"ls.home.onTimeRate"},{"code":"ls.home.openIssues"},{"code":"ls.home.myReviews"},{"code":"ls.home.teamWork"},{"code":"ls.home.quickLinks"}]}', now(), '演示数据'),
('home.quality_inspector', 1, null, 'HOME', '首页（质检员）', 'PUBLISHED',
 '{"cards":[{"code":"ls.home.openIssues"},{"code":"ls.home.myReviews"},{"code":"ls.home.quickLinks"}]}', now(), '演示数据')
on conflict do nothing;
