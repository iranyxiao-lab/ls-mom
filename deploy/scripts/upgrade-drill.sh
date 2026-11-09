#!/usr/bin/env bash
# 升级演练：把上一版本备份恢复到临时环境，用当前版本应用升级（核心迁移），安装 ChangeSet 的 Pack 制品并运行
# 全部已启用 Pack 的测试，再把沙箱 / 回归 / 越权 / 升级四个阶段的结果回写到 ChangeSet。任何一步失败，发布门禁都会阻止发布。
#
# 用法：deploy/scripts/upgrade-drill.sh --changeset <ID> (--backup <备份目录> | --backup-from <Compose 项目名>) [--keep]
#   --backup-from 先对指定项目（通常就是目标环境）执行 backup.sh，再用这份备份演练
#   目标环境地址：LSMOM_E2E_BASE（下载制品、回写结果；默认 http://127.0.0.1:18081）
#   演练环境：Compose 项目 lsmom-drill，端口 LSMOM_DRILL_PORT（默认 18091），结束后删除（--keep 保留以便排查）
set -euo pipefail
cd "$(dirname "$0")/.."
DEPLOY_DIR=$PWD
ROOT=$(cd .. && pwd)

CS='' BACKUP='' FROM='' KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --changeset) CS=$2; shift 2 ;;
    --backup) BACKUP=$2; shift 2 ;;
    --backup-from) FROM=$2; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
[ -n "$CS" ] && { [ -n "$BACKUP" ] || [ -n "$FROM" ]; } || { sed -n '5,8p' "$0" >&2; exit 2; }

TARGET=${LSMOM_E2E_BASE:-http://127.0.0.1:18081}
DRILL=lsmom-drill
DRILL_PORT=${LSMOM_DRILL_PORT:-18091}
DRILL_DB_PORT=${LSMOM_DRILL_DB_PORT:-15442}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/lsmom-drill.XXXXXX")
helper() { (cd "$ROOT/plus-ui" && node tests/e2e/pack-drill.mjs "$@"); }
ddc() {
  COMPOSE_PROJECT_NAME=$DRILL LSMOM_HTTP_PORT=$DRILL_PORT LSMOM_DB_PORT=$DRILL_DB_PORT CAPTCHA_ENABLED=false \
    LSMOM_PACK_DIRECT_INSTALL=true LSMOM_SEED_DEMO_ENABLED=false docker compose -p "$DRILL" "$@"
}
cleanup() {
  if [ "$KEEP" = 1 ]; then
    echo "保留演练环境：http://127.0.0.1:$DRILL_PORT（docker compose -p $DRILL down -v 删除）"
  else
    ddc down -v >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

report_and_exit() {
  echo "[5/5] 回写 ChangeSet #$CS"
  LSMOM_E2E_BASE=$TARGET helper report "$CS" "$WORK/report.json"
  LSMOM_E2E_BASE=$TARGET helper gate "$CS" || exit 1
}

echo "[1/5] 下载 ChangeSet #$CS 制品（$TARGET）"
LSMOM_E2E_BASE=$TARGET helper artifact "$CS" "$WORK/pack.zip" >/dev/null

if [ -n "$FROM" ]; then
  echo "[2/5] 备份 $FROM"
  BACKUP="$WORK/backup"
  COMPOSE_PROJECT_NAME=$FROM "$DEPLOY_DIR/scripts/backup.sh" "$BACKUP" >/dev/null
else
  echo "[2/5] 使用备份 $BACKUP"
fi
[ -f "$BACKUP/manifest.json" ] || { echo "备份目录无 manifest.json：$BACKUP" >&2; exit 1; }
[ "$(sha256sum "$BACKUP/db.dump" | cut -d' ' -f1)" = "$(python3 -c "import json;print(json.load(open('$BACKUP/manifest.json'))['database']['sha256'])")" ] \
  || { echo "备份校验和不一致" >&2; exit 1; }

echo "[3/5] 恢复到临时环境 $DRILL 并以当前版本启动（执行核心迁移）"
ddc down -v >/dev/null 2>&1 || true
ddc up -d postgres redis seaweedfs s3-init >/dev/null 2>&1
for _ in $(seq 1 90); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' "$(ddc ps -q postgres)" 2>/dev/null)" = healthy ] && break; sleep 2
done
ddc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -q \
  -c "drop database if exists \"$POSTGRES_DB\" with (force)" -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""'
ddc exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges --exit-on-error' < "$BACKUP/db.dump"
ddc up -d --no-deps app >/dev/null 2>&1
healthy=0
for _ in $(seq 1 150); do
  st=$(docker inspect -f '{{.State.Status}} {{.State.Health.Status}} {{.RestartCount}}' "$(ddc ps -aq app)" 2>/dev/null || echo "missing")
  case "$st" in
    "running healthy"*) healthy=1; break ;;
    exited*|dead*|missing) break ;;
  esac
  [ "$(awk '{print $3}' <<<"$st")" -gt 0 ] 2>/dev/null && break
  sleep 3
done
if [ "$healthy" != 1 ]; then
  LOG=$(ddc logs --no-color --tail 400 app 2>&1 || true)
  MIG=$(grep -oE 'Migration (of schema [^ ]+ to version "[^"]+"|[A-Za-z0-9_.]+\.sql) failed' <<<"$LOG" | head -1 || true)
  ERR=$(grep -m1 -E 'ERROR: |FlywayException|Caused by' <<<"$LOG" | cut -c1-500 || true)
  echo "✗ 演练环境启动失败（核心迁移）：${MIG:-未知} ${ERR}"
  LSMOM_E2E_BASE=$TARGET helper fail "$WORK/report.json" ALL "$(python3 -c 'import json,sys;print(json.dumps({"migration":sys.argv[1],"message":sys.argv[2]}))' "${MIG:-}" "${ERR:-演练环境未能启动}")"
  report_and_exit
  exit 1
fi
ddc up -d --no-deps web >/dev/null 2>&1
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$DRILL_PORT/prod-api/auth/code")" = 200 ] && break; sleep 2
done

echo "[4/5] 安装制品并运行测试（http://127.0.0.1:$DRILL_PORT）"
set +e
LSMOM_E2E_BASE=http://127.0.0.1:$DRILL_PORT helper run "$WORK/pack.zip" "$WORK/report.json"
RUN=$?
set -e
[ -f "$WORK/report.json" ] || { echo "演练未生成报告" >&2; exit 1; }
report_and_exit
exit $RUN
