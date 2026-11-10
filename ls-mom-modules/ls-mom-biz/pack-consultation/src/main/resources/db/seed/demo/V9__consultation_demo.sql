-- 演示：意见咨询权限。车间主任为议题负责人（发布议题、答复、转整改、确认总结）；质检员可答复；所有演示角色可提交意见。
-- 匿名意见的提交主体（ls:consultation:audit）不授予演示角色，仅管理员可查。仅在 lsmom.seed.demo.enabled=true 时执行。
insert into sys_role_menu (role_id, menu_id)
select r, m.menu_id from (values
  (2101, 'MANAGER'), (2105, 'REPLIER'), (2102, 'MEMBER'), (2103, 'MEMBER'), (2104, 'MEMBER'), (2106, 'MEMBER')) v(r, kind)
  join sys_menu m on m.menu_id between 3500 and 3599 and (
       kind = 'MANAGER' and m.perms <> 'ls:consultation:audit'
    or kind = 'REPLIER' and (m.menu_id in (3500, 3501, 3502, 3511, 3512) or m.perms in (
         'crud:ls.consultation.Topic:list', 'crud:ls.consultation.Topic:query', 'crud:ls.consultation.Opinion:list', 'crud:ls.consultation.Opinion:query',
         'crud:ls.consultation.Summary:query'))
    or kind = 'MEMBER' and (m.menu_id in (3500, 3501, 3512) or m.perms in ('crud:ls.consultation.Summary:query')))
 where exists (select 1 from sys_role where role_id = r)
on conflict do nothing;
