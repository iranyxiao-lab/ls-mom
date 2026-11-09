# Spec Delta

## Purpose

以只读方式关联 DCS／Historian 数据：维护点位语义与设备映射、保存降采样快照、对报警幂等去重并在人工确认后形成业务问题，同时保证高频原始数据不进入业务库、系统永不写入控制系统。

## ADDED Requirements

### Requirement: 只读边界
系统 SHALL 只从 DCS／Historian 读取数据；SHALL NOT 提供任何向 DCS／PLC 写值、下发参数或解除联锁的接口、Operation 或 Agent 工具。

#### Scenario: 写入能力扫描
- **WHEN** 对已发布 Operation 与 Agent 工具清单执行黑名单扫描
- **THEN** 不存在任何 DCS 写入类能力

### Requirement: 点位语义与映射
系统 SHALL 维护测点（编码、名称、单位、量程、来源系统）及测点与设备、车间、工艺的映射关系。

#### Scenario: 设备查看点位
- **WHEN** 打开设备详情
- **THEN** 显示映射到该设备的测点及最近快照值

### Requirement: 原始数据不入业务库
高频原始值 SHALL 保留在 Historian 或独立时序存储；业务库 SHALL 只保存明确时间窗口的降采样快照与聚合。

#### Scenario: 业务库检查
- **WHEN** 采集模拟器以 1Hz 连续推送 24 小时
- **THEN** 业务库中只有按配置粒度（默认 1 分钟）的快照记录

### Requirement: 报警幂等与去重
报警事件 SHALL 保留 `source_system`、`source_event_id`、`point_code`、`occurred_at`、`received_at`、`quality`、`dedup_key`、`trace_id` 及原始引用；同一 `dedup_key` 只保留一条业务报警并累加次数、更新最近发生时间。

#### Scenario: 乱序与重复
- **WHEN** 同一报警先后以乱序重复到达三次
- **THEN** 业务报警只有一条，次数为 3，最近发生时间为最大的 `occurred_at`

### Requirement: 采集链路健壮性
采集适配 SHALL 处理断网续传、重复、乱序、质量位异常与时钟偏差；质量位为坏值的数据 SHALL 标记且不参与报警判定。

#### Scenario: 断网续传
- **WHEN** 适配器断网 10 分钟后恢复
- **THEN** 缺失时段数据补传，快照无重复

#### Scenario: 时钟偏差
- **WHEN** 源时间戳比接收时间超前超过阈值
- **THEN** 记录被标记时钟异常并保留原始时间与接收时间

### Requirement: 报警处置策略
每个点位 SHALL 配置报警处置策略：自动生成 Issue 草稿、仅提醒或忽略；生成的 Issue SHALL 为草稿，经人工确认才成为正式问题。

#### Scenario: 报警生成草稿
- **WHEN** 配置为自动草稿的点位产生报警
- **THEN** 生成关联该报警与设备的 Issue 草稿，并通知设备负责人确认

### Requirement: 报警与人工缺陷关联去重
当人工已登记的设备缺陷与报警指向同一设备与时间窗口时，系统 SHALL 提示关联，确认后合并为同一 Issue 而不新建。

#### Scenario: 先人工后报警
- **WHEN** 巡检员已登记泵振动缺陷，10 分钟后该泵振动报警到达
- **THEN** 系统建议关联到已有缺陷，确认后报警挂在该 Issue 下

### Requirement: LLM 只用快照
提供给页面与 LLM 的 DCS 数据 SHALL 是明确时间范围的快照或聚合，并保留原始事件引用；LLM 摘要 SHALL NOT 替代原始事件。

#### Scenario: 报警上下文摘要
- **WHEN** 请求生成某报警的上下文摘要
- **THEN** 输入只含报警前后指定窗口的快照，摘要中带原始报警引用
