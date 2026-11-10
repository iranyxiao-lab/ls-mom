#!/usr/bin/env bash
# LS-MOM 联合恢复：先校验备份完整性，再覆盖业务库、对象存储桶，重启应用并核对恢复结果。
#
# 用法：deploy/scripts/restore.sh <备份目录> --yes
#   会删除当前 Compose 项目中的业务库并以备份替换，对象存储桶与备份保持一致（多余对象删除）。
#   目标环境可以是空环境：未启动的服务会先启动。
#   RESTORE_ALLOW_UPGRADE=1：用新版本应用恢复上一版本的备份（升级）。核心迁移只要求备份中的迁移全部保留，并列出新执行的迁移。
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=${1:-}
[ -n "$SRC" ] && [ -f "$SRC/manifest.json" ] || { echo "用法：$0 <备份目录> --yes" >&2; exit 2; }
SRC=$(cd "$SRC" && pwd)
[ "${2:-}" = "--yes" ] || { echo "恢复会覆盖当前数据库与对象存储，确认后加 --yes 重新执行" >&2; exit 2; }

AWS_CLI_IMAGE=${AWS_CLI_IMAGE:-amazon/aws-cli:2.17.0}
dc() { docker compose "$@"; }
cid() { dc ps -q "$1"; }
mf() { python3 -c "import json,sys;d=json.load(open('$SRC/manifest.json'));print(eval('d'+sys.argv[1]))" "$1"; }
s3_digest() { (cd "$1" && find . -type f -print0 | sort -z | xargs -0 -r sha256sum) | sha256sum | cut -d' ' -f1; }
wait_healthy() {
  for _ in $(seq 1 180); do
    [ "$(docker inspect -f '{{.State.Health.Status}}' "$(cid "$1")" 2>/dev/null)" = healthy ] && return 0
    sleep 2
  done
  echo "$1 未在超时内就绪" >&2; return 1
}

ALLOW_UPGRADE=${RESTORE_ALLOW_UPGRADE:-0}
echo "[1/5] 校验备份完整性"
[ "$(sha256sum "$SRC/db.dump" | cut -d' ' -f1)" = "$(mf "['database']['sha256']")" ] || { echo "db.dump 校验和不一致，备份已损坏" >&2; exit 1; }
[ "$(sha256sum "$SRC/packs.json" | cut -d' ' -f1)" = "$(mf "['packs']['sha256']")" ] || { echo "packs.json 校验和不一致" >&2; exit 1; }
[ "$(s3_digest "$SRC/s3")" = "$(mf "['objectStorage']['sha256']")" ] || { echo "对象存储备份校验和不一致" >&2; exit 1; }
BUCKET=$(mf "['objectStorage']['bucket']")

echo "[2/5] 准备基础服务，停止应用"
dc up -d postgres redis seaweedfs >/dev/null 2>&1
wait_healthy postgres; wait_healthy seaweedfs
dc stop web app >/dev/null 2>&1 || true

echo "[3/5] 恢复业务库"
dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 -q \
  -c "drop database if exists \"$POSTGRES_DB\" with (force)" -c "create database \"$POSTGRES_DB\" owner \"$POSTGRES_USER\""'
dc exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --no-privileges --exit-on-error' < "$SRC/db.dump"
RESTORED_MIGRATIONS=$(dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from flyway_schema_history where success"')

echo "[4/5] 恢复对象存储桶 $BUCKET"
NETWORK=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(cid seaweedfs)")
dc exec -T seaweedfs sh -c "echo s3.bucket.list | weed shell -master=127.0.0.1:9333 -filer=127.0.0.1:8888 | grep -qw '$BUCKET' || echo 's3.bucket.create -name $BUCKET' | weed shell -master=127.0.0.1:9333 -filer=127.0.0.1:8888" >/dev/null
S3_ENV=$(dc exec -T seaweedfs printenv S3_ACCESS_KEY S3_SECRET_KEY)
docker run --rm --network "$NETWORK" \
  -e AWS_ACCESS_KEY_ID="$(sed -n 1p <<<"$S3_ENV")" -e AWS_SECRET_ACCESS_KEY="$(sed -n 2p <<<"$S3_ENV")" \
  -e AWS_DEFAULT_REGION=us-east-1 -e HOME=/tmp -v "$SRC/s3:/backup:ro" \
  "$AWS_CLI_IMAGE" s3 sync /backup "s3://$BUCKET" --delete --endpoint-url http://seaweedfs:8333 --only-show-errors

echo "[5/5] 启动应用并核对"
dc up -d app web >/dev/null 2>&1
wait_healthy app; wait_healthy web
psql_q() { dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$0"' "$1"; }
CORE_NOW=$(psql_q "select coalesce(max(version), '') from flyway_schema_history where success and version is not null")
OBJ_NOW=$(docker run --rm --network "$NETWORK" \
  -e AWS_ACCESS_KEY_ID="$(sed -n 1p <<<"$S3_ENV")" -e AWS_SECRET_ACCESS_KEY="$(sed -n 2p <<<"$S3_ENV")" \
  -e AWS_DEFAULT_REGION=us-east-1 -e HOME=/tmp "$AWS_CLI_IMAGE" \
  s3 ls "s3://$BUCKET" --recursive --endpoint-url http://seaweedfs:8333 | wc -l)
if [ "$(psql_q "select to_regclass('meta_pack') is not null")" = t ]; then
  PACKS_NOW=$(psql_q "select coalesce(json_agg(json_build_object('code', code, 'version', version) order by code), '[]')::text from meta_pack")
else
  PACKS_NOW='[]'
fi
MIGRATIONS_NOW=$(psql_q "select count(*) from flyway_schema_history where success")
if [ "$ALLOW_UPGRADE" = 1 ]; then
  echo "  升级：恢复的迁移 $RESTORED_MIGRATIONS 个，新版本执行后共 $MIGRATIONS_NOW 个"
  psql_q "select '    + V' || version || ' ' || description from flyway_schema_history where success and installed_rank > $RESTORED_MIGRATIONS order by installed_rank"
  [ "$MIGRATIONS_NOW" -ge "$RESTORED_MIGRATIONS" ] || { echo "迁移记录少于备份" >&2; exit 1; }
fi
python3 - "$SRC/manifest.json" "$CORE_NOW" "$OBJ_NOW" "$PACKS_NOW" "$ALLOW_UPGRADE" <<'PY'
import json, sys
m, core, objs, packs = json.load(open(sys.argv[1])), sys.argv[2], int(sys.argv[3]), json.loads(sys.argv[4])
upgrade = sys.argv[5] == '1'
checks = [
    ('对象数量', m['objectStorage']['objects'], objs),
    ('已安装 Pack', m['packs']['installed'], packs),
]
if not upgrade:
    checks.insert(0, ('核心迁移版本', m['database']['coreMigration'], core))
bad = [c for c in checks if c[1] != c[2]]
for name, want, got in checks:
    print(f"  {'✓' if want == got else '✗'} {name}：备份 {want}，恢复后 {got}")
sys.exit(1 if bad else 0)
PY
echo "恢复完成：$SRC"
