#!/usr/bin/env bash
# LS-MOM 联合备份：业务库（含 flowable schema）＋对象存储桶＋Pack 清单，生成带校验和的 manifest.json。
#
# 用法：deploy/scripts/backup.sh [备份目录]
#   默认目录 deploy/backups/<时间戳>；Compose 项目名取 COMPOSE_PROJECT_NAME（或 .env）。
# 恢复：deploy/scripts/restore.sh <备份目录> --yes
set -euo pipefail
cd "$(dirname "$0")/.."
DEPLOY_DIR=$PWD

STAMP=$(date +%Y%m%d-%H%M%S)
OUT=${1:-$DEPLOY_DIR/backups/$STAMP}
mkdir -p "$OUT/s3"
OUT=$(cd "$OUT" && pwd)
if [ -e "$OUT/manifest.json" ]; then
  echo "目标目录已有备份：$OUT" >&2; exit 1
fi

AWS_CLI_IMAGE=${AWS_CLI_IMAGE:-amazon/aws-cli:2.17.0}
dc() { docker compose "$@"; }
cid() { dc ps -q "$1"; }
[ -n "$(cid postgres)" ] && [ -n "$(cid seaweedfs)" ] || { echo "postgres 或 seaweedfs 未运行" >&2; exit 1; }
NETWORK=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(cid seaweedfs)")
psql_q() { dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atc "$0"' "$1"; }

echo "[1/4] 导出业务库（public＋flowable schema）"
dc exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-privileges' > "$OUT/db.dump"
SCHEMAS=$(psql_q "select string_agg(nspname, ',' order by nspname) from pg_namespace where nspname in ('public','flowable')")
CORE_VER=$(psql_q "select coalesce(max(version), '') from flyway_schema_history where success and version is not null")
SEED_VER=$(psql_q "select coalesce((select max(version) from flyway_seed_history where success), '') where to_regclass('flyway_seed_history') is not null")

echo "[2/4] 导出 Pack 清单"
# meta_pack 由 Pack 生命周期模块创建；尚未创建时清单为空
if [ "$(psql_q "select to_regclass('meta_pack') is not null")" = t ]; then
  PACKS=$(psql_q "select coalesce(json_agg(row_to_json(p) order by p.code), '[]')::text from meta_pack p")
else
  PACKS='[]'
fi
printf '%s\n' "$PACKS" > "$OUT/packs.json"

echo "[3/4] 同步对象存储桶"
S3_ENV=$(dc exec -T seaweedfs printenv S3_ACCESS_KEY S3_SECRET_KEY)
BUCKET=${S3_BUCKET:-$(dc exec -T app printenv LSMOM_SEED_DEMO_S3_BUCKET 2>/dev/null || echo lsmom)}
docker run --rm --network "$NETWORK" --user "$(id -u):$(id -g)" \
  -e AWS_ACCESS_KEY_ID="$(sed -n 1p <<<"$S3_ENV")" -e AWS_SECRET_ACCESS_KEY="$(sed -n 2p <<<"$S3_ENV")" \
  -e AWS_DEFAULT_REGION=us-east-1 -e HOME=/tmp -v "$OUT/s3:/backup" \
  "$AWS_CLI_IMAGE" s3 sync "s3://$BUCKET" /backup --endpoint-url http://seaweedfs:8333 --only-show-errors

echo "[4/4] 生成 manifest.json"
s3_digest() { (cd "$1" && find . -type f -print0 | sort -z | xargs -0 -r sha256sum) | sha256sum | cut -d' ' -f1; }
python3 - "$OUT" "$STAMP" "$SCHEMAS" "$CORE_VER" "$SEED_VER" "$BUCKET" "$(s3_digest "$OUT/s3")" <<'PY'
import hashlib, json, os, sys
out, stamp, schemas, core, seed, bucket, s3sum = sys.argv[1:]
def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''): h.update(b)
    return h.hexdigest()
objects = sum(len(f) for _, _, f in os.walk(os.path.join(out, 's3')))
packs = json.load(open(os.path.join(out, 'packs.json')))
manifest = {
    'format': 'lsmom-backup/1', 'createdAt': stamp,
    'database': {'file': 'db.dump', 'sha256': sha(os.path.join(out, 'db.dump')),
                 'schemas': schemas.split(',') if schemas else [], 'coreMigration': core, 'seedMigration': seed},
    'objectStorage': {'bucket': bucket, 'dir': 's3', 'objects': objects, 'sha256': s3sum},
    'packs': {'file': 'packs.json', 'sha256': sha(os.path.join(out, 'packs.json')),
              'installed': [{'code': p.get('code'), 'version': p.get('version')} for p in packs]},
}
json.dump(manifest, open(os.path.join(out, 'manifest.json'), 'w'), ensure_ascii=False, indent=2)
print(json.dumps({k: manifest[k] for k in ('database', 'objectStorage')}, ensure_ascii=False))
PY
echo "备份完成：$OUT"
