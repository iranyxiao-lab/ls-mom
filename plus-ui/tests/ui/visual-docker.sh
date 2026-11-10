#!/usr/bin/env bash
# 在固定的 Playwright 容器中运行视觉回归（无头、字体一致）。本机与 CI 用同一镜像，基线才可比。
# 用法：tests/ui/visual-docker.sh [--update-snapshots]
#   LSMOM_E2E_BASE：静态页面截图的环境（默认 http://127.0.0.1:18081）
set -euo pipefail
cd "$(dirname "$0")/../.."
IMAGE=mcr.microsoft.com/playwright:v$(node -p "require('@playwright/test/package.json').version")-noble
[ -f storybook-static/index.json ] || pnpm storybook:build
docker run --rm --network host --ipc host -u "$(id -u):$(id -g)" -e HOME=/tmp -e CI="${CI:-}" -e LS_VISUAL_PAGES="${LS_VISUAL_PAGES:-}" \
  -e LSMOM_E2E_BASE="${LSMOM_E2E_BASE:-http://127.0.0.1:18081}" \
  -v "$PWD:$PWD" -w "$PWD" "$IMAGE" \
  node node_modules/@playwright/test/cli.js test --project=visual "$@"
