#!/usr/bin/env bash
# 19.5 性能基准环境：全新 Compose 项目 lsmom-perf → 安装基准台账 Pack（访客登记示例改名 acme.bench）→ 灌入 100 万行 →
# 运行 scripts/perf/bench.mjs（列表 50 并发、保存 30 并发）→ 报告写入 deploy/perf-reports/。
# 用法：deploy/scripts/perf-bench.sh [--rows 1000000] [--keep]（BENCH_SECONDS 每阶段秒数，默认 60）
set -euo pipefail
cd "$(dirname "$0")/.."
DEPLOY_DIR=$PWD
ROOT=$(cd .. && pwd)
ROWS=1000000 KEEP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --rows) ROWS=$2; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
P=lsmom-perf PORT=${LSMOM_PERF_PORT:-18097} DBPORT=${LSMOM_PERF_DB_PORT:-15447}
pdc() { COMPOSE_PROJECT_NAME=$P LSMOM_HTTP_PORT=$PORT LSMOM_DB_PORT=$DBPORT CAPTCHA_ENABLED=false LSMOM_PACK_DIRECT_INSTALL=true docker compose -p $P "$@"; }
psql_q() { pdc exec -T postgres psql -U lsmom -d lsmom -v ON_ERROR_STOP=1 -Atc "$1"; }
cleanup() { [ "$KEEP" = 1 ] && echo "保留基准环境：http://127.0.0.1:$PORT（docker compose -p $P down -v 删除）" || pdc down -v >/dev/null 2>&1 || true; }
trap cleanup EXIT

echo "[1/4] 启动全新基准环境 $P（端口 $PORT）"
pdc up -d postgres redis seaweedfs app web >/dev/null 2>&1
for _ in $(seq 1 180); do [ "$(docker inspect -f '{{.State.Health.Status}}' "$(pdc ps -q app)" 2>/dev/null)" = healthy ] && break; sleep 2; done

echo "[2/4] 安装基准台账 Pack acme.bench"
(cd "$ROOT/plus-ui" && LSMOM_E2E_BASE=http://127.0.0.1:$PORT node -e "
const { open } = await import('./tests/e2e/lib.mjs');
const { buildStd, b64 } = await import('./tests/e2e/upgrade-lib.mjs');
const a = await open();
const r = await a.api('POST', '/api/packs/install', { archive: b64(buildStd('acme.bench', '1.0.0')) });
await a.browser.close();
if (r.code !== 200) { console.error(r.msg); process.exit(1); }
console.log('  ' + r.data.code + ' ' + r.data.version + ' ' + r.data.status);
" --input-type=module)

echo "[3/4] 灌入 $ROWS 行"
ORG=$(psql_q "select id from core_org_unit order by id limit 1")
psql_q "insert into c_acme_bench_visit (org_id, status, created_by, created_at, version, ext_attrs, del_flag, visitor_name, company, host_name, visit_date, purpose)
  select $ORG, (array['REGISTERED','CHECKED_IN','CHECKED_OUT'])[1 + g % 3], 1, now() - (g || ' second')::interval, 0, '{}', '0',
         '访客' || g, (array['华东检测站','某供应商','设备厂家','外协单位甲','外协单位乙','物流公司','设计院','审计组'])[1 + g % 8],
         '被访人' || (g % 500), date '2026-11-10' - (g % 720), '基准数据'
    from generate_series(1, $ROWS) g" >/dev/null
psql_q "analyze c_acme_bench_visit" >/dev/null
echo "  共 $(psql_q "select count(*) from c_acme_bench_visit") 行"

echo "[4/4] 运行基准"
mkdir -p "$DEPLOY_DIR/perf-reports"
REPORT=$DEPLOY_DIR/perf-reports/$(date +%Y%m%d-%H%M%S).json
LSMOM_E2E_BASE=http://127.0.0.1:$PORT node "$ROOT/scripts/perf/bench.mjs" --concept acme.bench.Visit --rows "$ROWS" --out "$REPORT"
echo "报告：$REPORT"
