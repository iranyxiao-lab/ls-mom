#!/usr/bin/env bash
# 旅程 J-RESTORE / J-UPGRADE（acceptance-walkthrough 5.4，只用于测试环境）：
# acc-order: last（会重置验收环境，全量执行时排在最后）
#   1. 从上一版本（默认：最近一次新增迁移的提交的父提交，可用 LSMOM_PREV_REF 指定）构建镜像 lsmom/*:prev，
#      验收环境以该版本一键重置，并经模板→演练→批准→发布准备借还台账配置包；
#   2. upgrade-acceptance.sh：准备标准与客户扩展 Pack、带客户字段的记录、在途审批与附件，联合备份；
#      同版本（prev）恢复到全新环境并继续办理在途审批（J-RESTORE）；
#      用当前代码构建新版本，恢复并升级（核心迁移、Pack 1.0→1.1），在途流程按旧版本继续办理到归档（J-UPGRADE）；
#   3. 结束后验收环境以当前版本重置。
# 用法：acceptance/e2e/journeys/upgrade-restore.sh
# 输出中的 "✓ [J-RESTORE]" / "✓ [J-UPGRADE]" 行供 run-auto 判定用例通过。
set -euo pipefail
cd "$(dirname "$0")/../../.."
ROOT=$PWD
PORT=${LSMOM_ACC_PORT:-18101}
PROJECT=${LSMOM_ACC_PROJECT:-lsmom-acc}

PREV_REF=${LSMOM_PREV_REF:-$(git log -1 --format=%H --diff-filter=A -- '*/db/migration/*')^}
PREV_SHA=$(git rev-parse "$PREV_REF")
echo "== 上一版本：$(git log -1 --format='%h %s' "$PREV_SHA" | cut -c1-80)"

built() { [ "$(docker image inspect --format '{{index .Config.Labels "lsmom.ref"}}' "$1" 2>/dev/null || true)" = "$PREV_SHA" ]; }
if built lsmom/app:prev && built lsmom/web:prev; then
  echo "复用已构建的上一版本镜像"
else
  WT=$(mktemp -d "${TMPDIR:-/tmp}/lsmom-prev.XXXXXX")
  git worktree add --detach "$WT" "$PREV_SHA" >/dev/null
  trap 'git -C "$ROOT" worktree remove --force "$WT" >/dev/null 2>&1 || true' EXIT
  echo "== 构建上一版本镜像（$WT）"
  docker build -q -f "$WT/deploy/docker/app.Dockerfile" --build-arg MAVEN_MIRROR_URL="${MAVEN_MIRROR_URL:-}" --label lsmom.ref="$PREV_SHA" -t lsmom/app:prev "$WT" >/dev/null
  docker build -q -f "$WT/deploy/docker/web.Dockerfile" --build-arg NPM_REGISTRY="${NPM_REGISTRY:-}" --label lsmom.ref="$PREV_SHA" -t lsmom/web:prev "$WT" >/dev/null
fi

echo "== 验收环境以上一版本重置"
LSMOM_IMAGE_TAG=prev deploy/scripts/acceptance-reset.sh
# 在途流程依赖借还台账配置包
LSMOM_E2E_BASE=http://127.0.0.1:$PORT LSMOM_E2E_PROJECT=$PROJECT node plus-ui/tests/e2e/toolloan-config.e2e.mjs || { echo "✗ 借还台账配置包准备失败" >&2; exit 1; }

out=$(mktemp)
set +e
LSMOM_E2E_BASE=http://127.0.0.1:$PORT LSMOM_PREV_TAG=prev deploy/scripts/upgrade-acceptance.sh --from "$PROJECT" 2>&1 | tee "$out"
set -e
grep -q '［恢复］流程与业务状态对账无差异' "$out" && echo "✓ [J-RESTORE] 联合备份同版本恢复：记录、附件、Pack 与流程一致，在途流程继续办理到归档"
grep -q '升级与联合恢复验收通过' "$out" && echo "✓ [J-UPGRADE] 新版本恢复并升级：核心迁移、Pack 升级、客户字段保留，在途流程按旧版本继续办理到归档"
rm -f "$out"

echo "== 验收环境恢复为当前版本"
deploy/scripts/acceptance-reset.sh >/dev/null && echo "验收环境已重置为当前版本"
# 重置会清掉借还台账配置包；之后执行的界面用例与走查依赖它，在这里补回
LSMOM_E2E_BASE=http://127.0.0.1:$PORT LSMOM_E2E_PROJECT=$PROJECT node plus-ui/tests/e2e/toolloan-config.e2e.mjs >/dev/null || { echo "✗ 恢复后借还台账配置包准备失败" >&2; exit 1; }
