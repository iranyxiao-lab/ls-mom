#!/usr/bin/env bash
# 发布门禁：ChangeSet 的校验、沙箱、回归、越权、升级演练全部通过才返回 0；否则列出未通过项（含失败迁移）并返回 1。
# 发布接口本身也执行同样的检查，这个脚本用于 CI / 发布流程中提前拦截。
#
# 用法：deploy/scripts/release-gate.sh <ChangeSet ID> [--perf <性能报告.json>]（目标环境地址：LSMOM_E2E_BASE）
#   --perf：同时要求基准环境的性能报告（deploy/scripts/perf-bench.sh 生成）中列表与保存的 P95 均达标
set -euo pipefail
cd "$(dirname "$0")/../../plus-ui"
[ -n "${1:-}" ] || { echo "用法：$0 <ChangeSet ID> [--perf <性能报告.json>]" >&2; exit 2; }
CS=$1; shift
PERF=''
while [ $# -gt 0 ]; do
  case "$1" in
    --perf) PERF=$2; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
STATUS=0
node tests/e2e/pack-drill.mjs gate "$CS" || STATUS=1
if [ -n "$PERF" ]; then
  python3 - "$PERF" <<'PY' || STATUS=1
import json, sys
r = json.load(open(sys.argv[1]))
for k, name in (('list', '列表'), ('save', '保存')):
    x = r[k]
    print(f"  {'✓' if x['passed'] else '✗'} 性能：{name} 并发 {x['concurrency']} P95 {x['p95']}ms（≤{x['limitP95']}ms），错误 {x['errors']}")
sys.exit(0 if r['passed'] else 1)
PY
fi
exit $STATUS
