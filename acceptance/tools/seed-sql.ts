/**
 * 生成验收账号 SQL（输出到标准输出，由 acceptance-reset.sh 经管道交给 psql）：口令在第一条语句中于库内用 bcrypt 散列为 :acc_hash，后续语句只用散列值，
 * 不出现在命令行参数与日志中。账号编号从 2401 起按 accounts.yaml 顺序分配，保证每次重置一致。
 */
import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';
import { ROOT } from './lib.ts';

export interface AccAccount {
  user: string;
  name: string;
  dept: number;
  role: number;
  kind: string;
}

export function accounts(): { password: string; accounts: AccAccount[] } {
  return YAML.parse(fs.readFileSync(path.join(ROOT, 'deploy/seed/acceptance/accounts.yaml'), 'utf8'));
}

/** 走查脚本取口令（只在内存中使用）。 */
export const accPassword = () => accounts().password;

const q = (s: string) => `'${s.replace(/'/g, "''")}'`;

export function accountSql(): string {
  const { password, accounts: list } = accounts();
  // 口令只出现在第一条语句里，立即散列成 :acc_hash；VERBOSITY terse 让出错信息不回显语句原文
  const lines = [
    '\\set VERBOSITY terse',
    // 服务端出错时也不把语句原文写进数据库日志
    "set log_min_error_statement = 'panic';",
    'create extension if not exists pgcrypto;',
    `select crypt(${q(password)}, gen_salt('bf', 10)) as acc_hash \\gset`,
    'begin;',
    // 验收管理员角色：全部菜单权限、全部数据（上游超级管理员只认 user_id = 1，验收账号用独立角色）
    `insert into sys_role (role_id, tenant_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, remark)
values (2190, '000000', '验收管理员', 'acc_admin', 90, '1', 't', 't', '0', '0', 103, 1, now(), '验收种子：全部菜单') on conflict (role_id) do nothing;`,
    'insert into sys_role_menu (role_id, menu_id) select 2190, menu_id from sys_menu on conflict do nothing;'
  ];
  list.forEach((a, i) => {
    const id = 2401 + i;
    lines.push(
      `insert into sys_user (user_id, tenant_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, password, status, del_flag, create_dept, create_by, create_time, remark)
values (${id}, '000000', ${a.dept}, ${q(a.user)}, ${q(a.name)}, 'sys_user', '', '', '0', :'acc_hash', '0', '0', 103, 1, now(), ${q('验收账号：' + a.kind)})
on conflict (user_id) do update set dept_id = excluded.dept_id, user_name = excluded.user_name, nick_name = excluded.nick_name,
  password = excluded.password, status = '0', del_flag = '0', remark = excluded.remark;`,
      `delete from sys_user_role where user_id = ${id};`,
      `insert into sys_user_role (user_id, role_id) values (${id}, ${a.role});`
    );
  });
  lines.push('commit;');
  return lines.join('\n') + '\n';
}

if (import.meta.main) process.stdout.write(accountSql() + fs.readFileSync(path.join(ROOT, 'deploy/seed/acceptance/dataset.sql'), 'utf8'));
