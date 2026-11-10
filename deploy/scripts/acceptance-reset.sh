#!/usr/bin/env bash
# 验收环境一键重置（acceptance-walkthrough 2.1）：删除验收 Compose 项目及其数据卷 → 重新启动（迁移、标准 Pack、演示种子在应用启动时执行）
# → 导入 acceptance 种子数据集（deploy/seed/acceptance）→ 输出数据指纹（同一天连续两次重置的指纹应一致）。
# 用法：deploy/scripts/acceptance-reset.sh [--build]
#   环境变量：LSMOM_ACC_PROJECT（默认 lsmom-acc）、LSMOM_ACC_PORT（默认 18101）、LSMOM_ACC_DB_PORT（默认 15452）
# 只用于本地或 CI；口令从 deploy/seed/acceptance/accounts.yaml 经标准输入交给数据库，不出现在命令行与输出中。
set -euo pipefail
cd "$(dirname "$0")/.."
# 本脚本全部输出另存一份，结束时连同应用与数据库日志一起做口令泄露检查
LOG_DIR=${LSMOM_ACC_LOG_DIR:-$(cd .. && pwd)/acceptance/runs/reset}
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/reset-$(date +%Y%m%d-%H%M%S).log"
exec > >(tee "$LOG") 2>&1
ROOT=$(cd .. && pwd)
PROJECT=${LSMOM_ACC_PROJECT:-lsmom-acc}
PORT=${LSMOM_ACC_PORT:-18101}
DB_PORT=${LSMOM_ACC_DB_PORT:-15452}
BUILD=()
[ "${1:-}" = "--build" ] && BUILD=(--build)

compose() {
  CAPTCHA_ENABLED=false LSMOM_HTTP_PORT=$PORT LSMOM_DB_PORT=$DB_PORT LSMOM_DCS_ENABLED=true LSMOM_SEED_DEMO_ENABLED=true \
    docker compose -p "$PROJECT" --profile dcs "$@"
}
psql_acc() {
  compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' psql "$@"
}

echo "▶ 删除验收环境 $PROJECT（含数据卷）"
compose down -v --remove-orphans >/dev/null 2>&1 || true
echo "▶ 启动（迁移、标准 Pack、演示种子）"
compose up -d "${BUILD[@]}" >/dev/null
app=$(compose ps -q app)
for i in $(seq 1 180); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$app")" = healthy ] && break
  [ "$i" = 180 ] && { echo "应用未就绪"; exit 1; }
  sleep 3
done
echo "▶ 导入验收种子数据集"
(cd "$ROOT/acceptance" && node tools/seed-sql.ts) | psql_acc >/dev/null
echo "▶ 数据指纹"
psql_acc -At <<'SQL'
select 'accounts=' || count(*) from sys_user where user_id between 2401 and 2499;
select 'assets=' || count(*) from c_ls_asset_asset where asset_code like 'ACC-%';
select 'points=' || count(*) from dcs_point where code like 'ACC-%';
select 'fingerprint=' || md5(string_agg(x, '|' order by x)) from (
  select 'u:' || u.user_name || ':' || u.nick_name || ':' || u.dept_id || ':' || string_agg(ur.role_id::text, ',' order by ur.role_id)
    from sys_user u join sys_user_role ur on ur.user_id = u.user_id where u.user_id between 2401 and 2499 group by u.user_id
  union all select 'p:' || p.name || ':' || coalesce(o.code, '-') from core_party p
    left join core_party_org po on po.party_id = p.id and po.is_primary left join core_org_unit o on o.id = po.org_unit_id
   where p.user_id between 2401 and 2499 or p.code = 'AGENT-ACC'
  union all select 'a:' || a.asset_code || ':' || a.name || ':' || a.status || ':' || o.code from c_ls_asset_asset a join core_org_unit o on o.id = a.org_id where a.asset_code like 'ACC-%'
  union all select 'i:' || p.name || ':' || i.item_code || ':' || i.value_type from c_ls_asset_inspection_plan p join c_ls_asset_inspection_plan_item i on i.parent_id = p.id where p.name like '%验收%'
  union all select 'd:' || d.code || ':' || d.alarm_policy || ':' || a.asset_code from dcs_point d join c_ls_asset_asset a on a.id = d.asset_id where d.code like 'ACC-%'
  union all select 'g:' || code || ':' || permissions::text from agent_identity where code in ('acc-agent', 'shift-assistant')
  union all select 'k:' || code || ':' || version from meta_pack where status = 'ENABLED'
  union all select 'l:' || o.code || ':' || p.name from core_org_unit o join core_party p on p.id = o.leader_party_id where o.dept_id between 2000 and 2099
  union all select 'r:2190:' || count(*) from sys_role_menu where role_id = 2190
) t(x);
SQL
echo "▶ 口令泄露检查（本次输出、应用日志、数据库日志）"
compose logs --no-color app postgres > "$LOG_DIR/containers.log" 2>&1 || true
(cd "$ROOT/acceptance" && node tools/leak-check.ts "$LOG" "$LOG_DIR/containers.log")
rm -f "$LOG_DIR/containers.log"
echo "✓ 验收环境就绪：http://127.0.0.1:$PORT（账号见 deploy/seed/acceptance/accounts.yaml）"
