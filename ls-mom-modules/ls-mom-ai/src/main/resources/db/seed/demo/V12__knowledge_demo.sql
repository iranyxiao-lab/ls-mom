-- 演示：全部演示角色可问答；车间主任与实施顾问维护、发布知识库文档；交接班助手可问答。
-- 一份一车间的停机检修 SOP：v1 已发布，v2 修订稿未发布（问答只引用 v1）。分块由应用启动时补建。
insert into sys_role_menu (role_id, menu_id)
select r, m from (values
(2101, 3600), (2101, 3604), (2101, 3613), (2101, 3614), (2101, 3615),
(2106, 3604), (2106, 3613), (2106, 3614), (2106, 3615),
(2102, 3604), (2102, 3613),
(2103, 3600), (2103, 3604), (2103, 3613),
(2104, 3600), (2104, 3604), (2104, 3613),
(2105, 3600), (2105, 3604), (2105, 3613)
) v(r, m)
where exists (select 1 from sys_role where role_id = r)
on conflict do nothing;

update agent_identity set permissions = permissions || '["lsmom:knowledge:ask"]'::jsonb
 where code = 'shift-assistant' and not permissions @> '["lsmom:knowledge:ask"]'::jsonb;

insert into core_knowledge_document (code, title, category, org_unit_id, classification, published_version)
select 'SOP-WS1-LOTO', '一车间停机检修挂牌上锁规程', 'SOP', o.id, 'INTERNAL', 1
  from core_org_unit o where o.code = 'D2010'
on conflict (code) do nothing;

insert into core_knowledge_version (document_id, version, content, change_note, status, published_at)
select d.id, 1, '# 适用范围
一车间设备停机检修、清理堵料、更换皮带时适用。

# 作业前
检修前必须切断主电源并在隔离开关处挂牌上锁，钥匙由作业人本人保管。
确认能量已隔离：验电、泄压、释放储能弹簧后方可作业。

# 作业后
清点工具、撤离人员后由挂牌人本人解锁摘牌，恢复送电前通知班组长。', '初版', 'PUBLISHED', now()
  from core_knowledge_document d where d.code = 'SOP-WS1-LOTO'
on conflict (document_id, version) do nothing;

insert into core_knowledge_version (document_id, version, content, change_note, status)
select d.id, 2, '# 适用范围
一车间设备停机检修、清理堵料、更换皮带时适用。

# 作业前
检修前必须切断主电源并挂牌上锁；涉及联锁解除的，须由双人确认并在交接记录签字（修订中）。', '增加联锁解除双人确认（待审）', 'DRAFT'
  from core_knowledge_document d where d.code = 'SOP-WS1-LOTO'
on conflict (document_id, version) do nothing;
