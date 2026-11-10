-- 从模板 / Excel 生成的草稿带授权清单（按钮权限 → 角色）：生成人没有角色管理权限时由管理员授权，授权完成前发布门禁不放行
alter table meta_change_set add column grant_plan jsonb;
alter table meta_change_set add column grants_applied_at timestamp;
alter table meta_change_set add column grants_applied_by bigint;
comment on column meta_change_set.grant_plan is '台账需要的按钮权限与授予角色（PermissionGrants.plan）';
comment on column meta_change_set.grants_applied_at is '授权完成时间；有授权清单而未授权时不能发布';
