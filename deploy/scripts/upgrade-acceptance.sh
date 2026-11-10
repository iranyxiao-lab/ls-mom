#!/usr/bin/env bash
# 19.3 / 19.4 升级与联合恢复验收（只用于测试环境）：
#   1. 在"上一版本"环境（默认 lsmom-verify，运行中的旧镜像）经 ChangeSet 发布标准 Pack 与客户扩展 Pack，
#      登记带客户字段的记录、发起在途审批、上传附件；
#   2. 联合备份（数据库含 flowable schema＋对象存储＋Pack 清单）；
#   3. 19.4 同版本恢复到全新环境 lsmom-restore：记录、附件、Pack 与流程定义一致，在途流程继续办理到归档；
#   4. 构建新版本镜像；
#   5. 19.3 用新版本恢复到全新环境 lsmom-upgrade：核心迁移执行，标准 Pack 与客户 Pack 1.0→1.1 升级，
#      客户字段与数据保留、验收测试通过、无漂移，在途流程继续办理到归档。
#
# 用法：deploy/scripts/upgrade-acceptance.sh [--from <Compose 项目>] [--skip-build] [--keep]
#   上一版本环境地址：LSMOM_E2E_BASE（默认 http://127.0.0.1:18081）
#   上一版本镜像标签：LSMOM_PREV_TAG（上一版本环境用 LSMOM_IMAGE_TAG 起的镜像；同版本恢复用它，新版本构建为 local）
set -euo pipefail
cd "$(dirname "$0")/.."
DEPLOY_DIR=$PWD
ROOT=$(cd .. && pwd)

FROM=lsmom-verify BUILD=1 KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM=$2; shift 2 ;;
    --skip-build) BUILD=0; shift ;;
    --keep) KEEP=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
FROM_BASE=${LSMOM_E2E_BASE:-http://127.0.0.1:18081}
PREV_TAG=${LSMOM_PREV_TAG:-local}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/lsmom-acceptance.XXXXXX")
STATE=$WORK/state.json

env_for() { # 项目名 HTTP 端口 DB 端口
  echo "COMPOSE_PROJECT_NAME=$1 LSMOM_HTTP_PORT=$2 LSMOM_DB_PORT=$3 CAPTCHA_ENABLED=false LSMOM_PACK_DIRECT_INSTALL=true LSMOM_DCS_ENABLED=false"
}
down() { env $(env_for "$1" 0 0) docker compose -p "$1" down -v >/dev/null 2>&1 || true; }
cleanup() {
  if [ "$KEEP" = 1 ]; then
    echo "保留：lsmom-restore（18095）、lsmom-upgrade（18096），备份在 $WORK/backup"
  else
    down lsmom-restore; down lsmom-upgrade; rm -rf "$WORK"
  fi
}
trap cleanup EXIT
e2e() { (cd "$ROOT/plus-ui" && node "tests/e2e/$1"); }

echo "== 1. 上一版本环境 $FROM（$FROM_BASE）：准备 Pack、客户字段数据、在途流程与附件"
LSMOM_E2E_BASE=$FROM_BASE LSMOM_E2E_PROJECT=$FROM LSMOM_E2E_STATE=$STATE e2e upgrade-prepare.mjs

echo "== 2. 联合备份"
COMPOSE_PROJECT_NAME=$FROM scripts/backup.sh "$WORK/backup"

echo "== 3. [19.4] 同版本恢复到全新环境 lsmom-restore"
down lsmom-restore
env $(env_for lsmom-restore 18095 15445) LSMOM_IMAGE_TAG=$PREV_TAG scripts/restore.sh "$WORK/backup" --yes
LSMOM_E2E_BASE=http://127.0.0.1:18095 LSMOM_E2E_PROJECT=lsmom-restore LSMOM_E2E_STATE=$STATE MODE=restore e2e upgrade-verify.mjs
[ "$KEEP" = 1 ] || down lsmom-restore

if [ "$BUILD" = 1 ]; then
  echo "== 4. 构建新版本镜像"
  LSMOM_IMAGE_TAG=local docker compose -p "$FROM" build app web >/dev/null
fi

echo "== 5. [19.3] 新版本恢复上一版本备份到 lsmom-upgrade 并升级"
down lsmom-upgrade
env $(env_for lsmom-upgrade 18096 15446) LSMOM_IMAGE_TAG=local RESTORE_ALLOW_UPGRADE=1 scripts/restore.sh "$WORK/backup" --yes
LSMOM_E2E_BASE=http://127.0.0.1:18096 LSMOM_E2E_PROJECT=lsmom-upgrade LSMOM_E2E_STATE=$STATE MODE=upgrade e2e upgrade-verify.mjs

echo "升级与联合恢复验收通过"
