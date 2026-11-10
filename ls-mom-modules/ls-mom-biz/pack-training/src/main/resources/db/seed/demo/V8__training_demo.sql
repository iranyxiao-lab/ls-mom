-- 演示：培训考试权限。实施顾问为培训管理员（课程、题库、组卷、阅卷）；车间主任审批发布、阅卷、查资质；班组长、操作工、设备员、质检员参加考试。
-- 发布审批的办理人是课程 / 考试所属组织的负责人：演示工厂与一车间负责人设为周主任（未设置时）。仅在 lsmom.seed.demo.enabled=true 时执行。
update sys_dept set leader = (select user_id from sys_user where user_name = 'demo_leader')
 where dept_id in (2000, 2010) and leader is null and exists (select 1 from sys_user where user_name = 'demo_leader');

insert into sys_role_menu (role_id, menu_id)
select r, m.menu_id from (values
  (2106, 'ALL'), (2101, 'LEADER'), (2102, 'TAKER'), (2103, 'TAKER'), (2104, 'TAKER'), (2105, 'TAKER')) v(r, kind)
  join sys_menu m on m.menu_id between 3400 and 3499 and (
       kind = 'ALL' and m.perms <> 'ls:training:approve'
    or kind = 'LEADER' and (m.menu_id in (3400, 3401, 3402, 3411, 3412, 3413, 3414) or m.perms in (
         'crud:ls.training.Course:list', 'crud:ls.training.Course:query', 'crud:ls.training.Exam:list', 'crud:ls.training.Exam:query',
         'crud:ls.training.Answer:list', 'crud:ls.training.Answer:query', 'crud:ls.training.TrainingRecord:list', 'crud:ls.training.TrainingRecord:query'))
    or kind = 'TAKER' and (m.menu_id in (3400, 3401, 3412) or m.perms in ('crud:ls.training.Answer:query', 'crud:ls.training.TrainingRecord:query',
         'crud:ls.training.TrainingRecord:list')))
 where exists (select 1 from sys_role where role_id = r)
on conflict do nothing;
