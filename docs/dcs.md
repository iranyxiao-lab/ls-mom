# DCS 只读集成

系统只从 DCS / Historian **读取**数据，永不写入控制系统：没有任何向 DCS / PLC 写值、下发参数或解除联锁的接口、Operation 或 Agent 工具。CI 用 `scripts/ci/scan-dcs-write.py` 扫描并拦截（规则见 `scripts/ci/dcs-write-denylist.txt`）。

## 数据流

```
现场 DCS / 模拟器 ──写──▶ Historian（独立时序库 TimescaleDB：historian_point_value / historian_alarm_event）
                                   │ 只读账号 lsmom_reader，连接为只读事务
                                   ▼
              ls-mom-dcs 增量读取（按接收时间游标，每 10 秒）
                ├─ 降采样 → 业务库 dcs_telemetry_snapshot（默认 60 秒一窗）
                └─ 报警接入 → dcs_alarm_event（原始事件引用）/ dcs_alarm（业务报警）→ 处置策略
```

高频原始值只留在 Historian；业务库只有分钟快照与报警引用。1Hz 推送 24 小时，每个测点在业务库只有 1440 行快照。

## 健壮性

| 场景 | 处理 |
|---|---|
| 断网续传 | 按接收时间增量读取并回看 2 分钟；补传数据所在时间窗整窗重算，按主键覆盖，快照无重复 |
| 重复送达 | 采样按 (测点, 源时间) 去重；报警事件按 (source_system, source_event_id) 只记一次 |
| 乱序 | 同一 `dedup_key` 只有一条业务报警，累加次数，最近发生时间取最大的 `occurred_at` |
| 坏质量位 | 采样计入 `bad_count` 不参与统计；报警事件记录但不参与判定 |
| 时钟偏差 | 源时间超前接收时间超过 `clock-skew-seconds`（默认 60）的标记时钟异常，保留原始时间与接收时间；采样计入 `skew_count` 不参与统计 |

## 测点与处置策略

测点（编码、名称、单位、量程、来源系统）映射到设备、组织、工艺。新报警按测点策略处置：

- **自动草稿**（AUTO_DRAFT）：生成 `DCS_ALARM` 类型的 Issue 草稿，关联设备与报警，通知设备负责人；草稿经人工确认（`ls.core.Issue.submit`）才成为正式问题。报警事件引用即证据，高严重度也不必另传照片。
- **仅提醒**（NOTIFY）：只通知负责人；可在报警页人工生成草稿。
- **忽略**（IGNORE）。

负责人取测点上指定的负责人，否则取设备台账的负责人。

**先人工后报警**：同一设备在 `link-window-minutes`（默认 30）内已有人工登记的未结问题时，报警不新建问题，标记「建议关联」；确认后报警挂在该 Issue 下，后续发生只累计次数。

## 接口（`/api/dcs`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/points?assetId=&keyword=` | `ls:dcs:view` | 测点及最近快照 |
| PUT | `/points` | `ls:dcs:manage` | 新增 / 修改测点语义与映射（只改业务库） |
| GET | `/points/{code}/snapshots?from=&to=` | `ls:dcs:view` | 快照，默认最近 6 小时，最长 7 天 |
| GET | `/alarms?status=&assetId=&pointCode=` | `ls:dcs:view` | 业务报警 |
| GET | `/alarms/{id}` | `ls:dcs:view` | 报警及原始事件引用 |
| GET | `/alarms/{id}/context?beforeMinutes=&afterMinutes=` | `ls:dcs:view` | 前后窗口快照＋原始事件引用（页面与 LLM 摘要的输入，不含原始高频值） |
| POST | `/alarms/{id}/link`、`/draft`、`/ignore` | `ls:dcs:handle` | 关联已有问题 / 生成草稿 / 忽略 |

均按调用者数据域过滤测点与报警的所属组织。

## 配置（`lsmom.dcs.*`）

| 属性 | 环境变量 | 默认 |
|---|---|---|
| `enabled` | `LSMOM_DCS_ENABLED` | false（不启用时只提供已有数据查询） |
| `historian-url` / `-user` / `-password` | `LSMOM_DCS_HISTORIAN_URL` / `_USER` / `_PASSWORD` | compose 中为 `tsdb` 与只读账号 `lsmom_reader` |
| `historian-schema` | `LSMOM_DCS_HISTORIAN_SCHEMA` | public |
| `snapshot-seconds` | `LSMOM_DCS_SNAPSHOT_SECONDS` | 60（须整除 3600） |
| `clock-skew-seconds`、`link-window-minutes`、`poll-ms`、`overlap-seconds` | — | 60、30、10000、120 |

## 本地演示

```bash
cd deploy && LSMOM_DCS_ENABLED=true docker compose --profile dcs up -d --build
```

`dcs-simulator` 首次启动回放最近 `DCS_BACKFILL_HOURS`（默认 6）小时数据，然后 1Hz 实时推送 P-101 的振动、轴承温度、出口压力；实时模式下启动 1 小时后断网 10 分钟并补传，另有重复送达、报警乱序、温度坏值、压力时钟超前 10 分钟。模拟器配置见 `ls-mom-dcs-simulator/src/main/resources/simulator.yaml`，可用 `--config` 替换。

演示种子预置 `P101.VIB`、`P101.TEMP`（自动草稿）与 `P101.PRESS`（仅提醒），归一车间、设备员负责；在「DCS → 测点」页把测点映射到设备后，设备详情即显示测点与最近快照。端到端验收：`pnpm test:e2e:dcs`（需 dcs profile）。
