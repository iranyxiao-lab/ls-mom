-- 演示：实施顾问可配置应用（安装、设计台账/表单/流程/指标/规则），不含评审、发布与补偿重投（由管理员或评审人负责）。
insert into sys_role_menu (role_id, menu_id)
select 2106, m from unnest(array[3800, 3801, 3803, 3804, 3807, 3810, 3811, 3812]) m
where exists (select 1 from sys_role where role_id = 2106)
on conflict do nothing;
