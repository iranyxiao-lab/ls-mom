#!/usr/bin/env bash
# 重新执行演示种子：重启 app（启动时执行未执行过的种子脚本），并输出种子执行历史与数据统计
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose restart app >/dev/null
for _ in $(seq 1 120); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q app)")" = healthy ] && break
  sleep 2
done
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "select version, description, success from flyway_seed_history order by installed_rank" -c "select (select count(*) from sys_dept where dept_id between 2000 and 2099) as depts, (select count(*) from sys_role where role_id between 2100 and 2199) as roles, (select count(*) from sys_user where user_id between 2200 and 2299) as users, (select count(*) from sys_dict_data where dict_type in ('"'"'ls_shift'"'"','"'"'ls_work_type'"'"')) as dict_items"'
