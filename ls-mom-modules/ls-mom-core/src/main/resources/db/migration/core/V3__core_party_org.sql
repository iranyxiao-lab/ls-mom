-- 稳定核心：主体（Party）与组织单元（OrgUnit）
-- 组织单元与系统部门一一映射（复用 RuoYi 数据权限）；登录用户一一映射到 EMPLOYEE 主体。
-- 映射由触发器维护：无论经 RuoYi 管理界面、种子脚本还是 LS 服务创建，都不会遗漏。

create table core_org_unit
(
    id              bigserial primary key,
    code            varchar(64)  not null unique,
    name            varchar(100) not null,
    unit_type       varchar(16)  not null check (unit_type in ('COMPANY', 'DEPARTMENT', 'WORKSHOP', 'TEAM', 'SECTION')),
    parent_id       bigint references core_org_unit (id),
    dept_id         bigint       not null unique,
    leader_party_id bigint,
    valid_from      date         not null default date '2000-01-01',
    valid_to        date,
    created_at      timestamp    not null default current_timestamp,
    updated_at      timestamp    not null default current_timestamp,
    version         int          not null default 0,
    check (valid_to is null or valid_to >= valid_from)
);
create index idx_core_org_unit_parent on core_org_unit (parent_id);
comment on table core_org_unit is '组织单元：公司/部门/车间/班组/工段，与 sys_dept 一一映射';
comment on column core_org_unit.dept_id is '映射的系统部门，数据权限复用 RuoYi 部门';
comment on column core_org_unit.valid_to is '失效日期（含当日有效）；失效后不能被新业务引用，历史记录仍可读';
comment on column core_org_unit.leader_party_id is '负责人主体；为空时回退到 sys_dept.leader';

create table core_party
(
    id          bigserial primary key,
    code        varchar(64)  not null unique,
    party_type  varchar(16)  not null check (party_type in ('EMPLOYEE', 'OUTSOURCE', 'SERVICE', 'AGENT', 'EXTERNAL')),
    name        varchar(100) not null,
    user_id     bigint unique,
    phone       varchar(32),
    org_name    varchar(100),
    status      varchar(16)  not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at  timestamp    not null default current_timestamp,
    updated_at  timestamp    not null default current_timestamp,
    version     int          not null default 0,
    -- 只有员工（及服务/Agent 身份）可关联登录账号；外部协作方不能登录
    check (user_id is null or party_type in ('EMPLOYEE', 'SERVICE', 'AGENT'))
);
comment on table core_party is '统一主体：人员、外部协作方、服务身份、Agent 身份；本身不承担权限判定';
comment on column core_party.user_id is '关联的登录账号（sys_user.user_id），为空表示不能登录';
comment on column core_party.org_name is '外部协作方所属单位名称';

alter table core_org_unit
    add constraint fk_core_org_unit_leader foreign key (leader_party_id) references core_party (id);

create table core_party_org
(
    id          bigserial primary key,
    party_id    bigint      not null references core_party (id),
    org_unit_id bigint      not null references core_org_unit (id),
    role        varchar(16) not null default 'MEMBER' check (role in ('MEMBER', 'LEADER', 'DEPUTY')),
    is_primary  boolean     not null default false,
    valid_from  date        not null default current_date,
    valid_to    date,
    unique (party_id, org_unit_id, role)
);
create unique index uk_core_party_org_primary on core_party_org (party_id) where is_primary;
comment on table core_party_org is '主体所属组织；主组织随登录账号的部门同步';

-- 部门 → 组织单元
create or replace function core_sync_org_unit_from_dept() returns trigger as
$$
declare
    v_type   varchar(16);
    v_parent bigint;
begin
    select id into v_parent from core_org_unit where dept_id = new.parent_id;
    v_type := case
                  when upper(coalesce(new.dept_category, '')) in ('COMPANY', 'DEPARTMENT', 'WORKSHOP', 'TEAM', 'SECTION')
                      then upper(new.dept_category)
                  when coalesce(new.parent_id, 0) = 0 then 'COMPANY'
                  else 'DEPARTMENT' end;
    if tg_op = 'INSERT' then
        insert into core_org_unit (code, name, unit_type, parent_id, dept_id, valid_to)
        values ('D' || new.dept_id, new.dept_name, v_type, v_parent, new.dept_id,
                case when new.del_flag <> '0' then current_date - 1 end)
        on conflict (dept_id) do nothing;
    else
        update core_org_unit
        set name       = new.dept_name,
            unit_type  = v_type,
            parent_id  = v_parent,
            -- 删除部门视为组织失效（保留历史可读）
            valid_to   = case when new.del_flag <> '0' and valid_to is null then current_date - 1 else valid_to end,
            updated_at = current_timestamp,
            version    = version + 1
        where dept_id = new.dept_id;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_core_org_unit_from_dept
    after insert or update of dept_name, dept_category, parent_id, del_flag on sys_dept
    for each row execute function core_sync_org_unit_from_dept();

-- 登录账号 → 员工主体，部门 → 主组织
create or replace function core_sync_party_from_user() returns trigger as
$$
declare
    v_party bigint;
    v_org   bigint;
begin
    if tg_op = 'INSERT' then
        insert into core_party (code, party_type, name, user_id, phone, status)
        values ('U' || new.user_id, 'EMPLOYEE', coalesce(nullif(new.nick_name, ''), new.user_name), new.user_id,
                nullif(new.phonenumber, ''), case when new.del_flag = '0' then 'ACTIVE' else 'INACTIVE' end)
        on conflict (user_id) do nothing;
    else
        update core_party
        set name       = coalesce(nullif(new.nick_name, ''), new.user_name),
            phone      = nullif(new.phonenumber, ''),
            status     = case when new.del_flag = '0' then 'ACTIVE' else 'INACTIVE' end,
            updated_at = current_timestamp,
            version    = version + 1
        where user_id = new.user_id;
    end if;

    select id into v_party from core_party where user_id = new.user_id;
    select id into v_org from core_org_unit where dept_id = new.dept_id;
    if tg_op = 'INSERT' or new.dept_id is distinct from old.dept_id then
        update core_party_org set is_primary = false, valid_to = current_date - 1
        where party_id = v_party and is_primary and role = 'MEMBER';
        if v_org is not null then
            insert into core_party_org (party_id, org_unit_id, role, is_primary)
            values (v_party, v_org, 'MEMBER', true)
            on conflict (party_id, org_unit_id, role) do update set is_primary = true, valid_to = null;
        end if;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_core_party_from_user
    after insert or update of nick_name, user_name, phonenumber, dept_id, del_flag on sys_user
    for each row execute function core_sync_party_from_user();

-- 回填已有部门（按层级顺序，保证父节点先建）与账号
insert into core_org_unit (code, name, unit_type, parent_id, dept_id)
select 'D' || d.dept_id, d.dept_name,
       case when upper(coalesce(d.dept_category, '')) in ('COMPANY', 'DEPARTMENT', 'WORKSHOP', 'TEAM', 'SECTION')
                then upper(d.dept_category)
            when coalesce(d.parent_id, 0) = 0 then 'COMPANY'
            else 'DEPARTMENT' end,
       null, d.dept_id
from sys_dept d
where d.del_flag = '0';
update core_org_unit o
set parent_id = p.id
from sys_dept d
         join core_org_unit p on p.dept_id = d.parent_id
where o.dept_id = d.dept_id;

insert into core_party (code, party_type, name, user_id, phone)
select 'U' || u.user_id, 'EMPLOYEE', coalesce(nullif(u.nick_name, ''), u.user_name), u.user_id, nullif(u.phonenumber, '')
from sys_user u
where u.del_flag = '0';
insert into core_party_org (party_id, org_unit_id, role, is_primary)
select p.id, o.id, 'MEMBER', true
from sys_user u
         join core_party p on p.user_id = u.user_id
         join core_org_unit o on o.dept_id = u.dept_id
where u.del_flag = '0';

-- 由 LS 服务新建的部门编号与非员工主体编码
create sequence core_dept_id_seq start with 900000000;
create sequence core_party_code_seq start with 1000;
