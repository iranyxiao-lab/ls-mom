#!/usr/bin/env bash
# 发布门禁：ChangeSet 的校验、沙箱、回归、越权、升级演练全部通过才返回 0；否则列出未通过项（含失败迁移）并返回 1。
# 发布接口本身也执行同样的检查，这个脚本用于 CI / 发布流程中提前拦截。
#
# 用法：deploy/scripts/release-gate.sh <ChangeSet ID>（目标环境地址：LSMOM_E2E_BASE）
set -euo pipefail
cd "$(dirname "$0")/../../plus-ui"
[ -n "${1:-}" ] || { echo "用法：$0 <ChangeSet ID>" >&2; exit 2; }
node tests/e2e/pack-drill.mjs gate "$1"
