-- 演示：DCS 菜单授权与 P-101 循环泵测点（一车间，设备员王工负责）。设备映射在「测点」页维护。
-- 车间主任、设备员、实施顾问可处置报警；设备员、实施顾问可维护测点；班组长、质检员只读。仅在 lsmom.seed.demo.enabled=true 时执行。
insert into sys_role_menu (role_id, menu_id)
select r, m from (values
(2101, 3300), (2101, 3301), (2101, 3302), (2101, 3311),
(2102, 3300), (2102, 3301), (2102, 3302),
(2104, 3300), (2104, 3301), (2104, 3302), (2104, 3310), (2104, 3311),
(2105, 3300), (2105, 3301), (2105, 3302),
(2106, 3300), (2106, 3301), (2106, 3302), (2106, 3310), (2106, 3311)
) v(r, m)
where exists (select 1 from sys_role where role_id = r)
on conflict do nothing;

insert into dcs_point (code, name, unit, range_low, range_high, source_system, org_unit_id, process_name, alarm_policy, owner_party)
select v.code, v.name, v.unit, v.lo, v.hi, 'DCS-A', o.id, '循环水', v.policy,
       (select p.id from core_party p join sys_user u on u.user_id = p.user_id where u.user_name = 'demo_asset')
  from (values ('P101.VIB', 'P-101 振动', 'mm/s', 0, 20, 'AUTO_DRAFT'),
               ('P101.TEMP', 'P-101 轴承温度', '℃', 0, 150, 'AUTO_DRAFT'),
               ('P101.PRESS', 'P-101 出口压力', 'MPa', 0, 1.6, 'NOTIFY')) v(code, name, unit, lo, hi, policy)
  join core_org_unit o on o.dept_id = 2010
on conflict (code) do nothing;
