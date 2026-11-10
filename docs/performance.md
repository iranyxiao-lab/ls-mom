# 性能基准

## 指标

| 场景 | 数据量 | 并发 | 门槛 |
|---|---|---|---|
| 通用列表（`POST /api/crud/{concept}/query`，按公司过滤、按日期倒序、分页 20 行） | 100 万行 | 50 | P95 ≤ 800 ms，零错误 |
| 通用保存（`POST /api/crud/{concept}`） | 100 万行 | 30 | P95 ≤ 500 ms，零错误 |

## 运行

```bash
deploy/scripts/perf-bench.sh
```

脚本会：

1. 新建独立的 Compose 项目 `lsmom-perf`（端口 18097，数据库 15447）。
2. 安装基准台账 Pack `acme.bench`。
3. 灌入 100 万行并 `ANALYZE`。
4. 运行 `scripts/perf/bench.mjs`，每阶段预热 10 秒后计时 60 秒。
5. 把报告写入 `deploy/perf-reports/<时间>.json`，结束后删除环境。

常用参数：

- `--rows N`：数据量。
- `--keep`：保留环境，方便排查。
- `BENCH_SECONDS`：每阶段的计时秒数。

未达标时退出码为 1。

发布门禁可以带上报告，报告中 `passed` 为 false 时门禁失败：

```bash
deploy/scripts/release-gate.sh <ChangeSet ID> --perf deploy/perf-reports/<时间>.json
```

## 基线（2026-11-10，单机 Docker）

报告：`deploy/perf-reports/20261110-133328.json`

| 场景 | 请求数 | 吞吐 | P50 | P95 | P99 | 错误 |
|---|---|---|---|---|---|---|
| 列表 50 并发 | 22,938 | 382/s | 132 ms | 205 ms | 252 ms | 0 |
| 保存 30 并发 | 459,175 | 7,653/s | 4 ms | 6 ms | 9 ms | 0 |

## 压测中发现并修复的问题

- **连接池互等死锁**
  - 现象：`ConceptRegistry.find` 在 RowMapper 回调里查询字段，一个请求同时占用两个数据库连接。并发超过连接池容量（20）时，所有线程都持有一个连接、等待第二个，全部超时（30 秒）。
  - 修复：先读完主行，再查字段。
  - 规则：**不要在 RowMapper / ResultSetExtractor 里发起新的查询。**
- **nginx 到后端的本地端口耗尽**
  - 现象：`proxy_pass` 没有配置上游长连接，每个请求都新建一条 TCP 连接。高并发下报 `connect() failed (99: Address not available)`，返回 502。
  - 修复：`deploy/docker/nginx.conf` 改用 `upstream lsmom_app { keepalive 128; }`。
- **Postgres 共享内存不足**
  - 现象：百万行上的并发 `count(*)` 会使用并行查询，Docker 默认的 64 MB `/dev/shm` 不够，报 `could not resize shared memory segment`。
  - 修复：Compose 为 postgres 设置 `shm_size`，默认 512m，可用 `DB_SHM_SIZE` 调整。
