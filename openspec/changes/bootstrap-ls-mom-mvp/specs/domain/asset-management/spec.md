# Spec Delta

## Purpose

提供设备／工位台账、状态、点检计划与记录、缺陷登记，并以 Issue 和 WorkItem 复用完成缺陷分派、维修与验证关闭。

## ADDED Requirements

### Requirement: 设备台账
系统 SHALL 维护设备台账（编码、名称、类型、位置、所属车间、状态、负责人），设备状态变更 SHALL 经 Operation 并保留历史。

#### Scenario: 状态变更留痕
- **WHEN** 设备由运行改为停机检修
- **THEN** 状态历史中新增一条带操作人与原因的记录

### Requirement: 点检计划与记录
系统 SHALL 支持点检计划（周期、检查项清单、责任人）按周期生成点检工作，点检记录逐项填写结果并可产生 Observation。

#### Scenario: 点检异常项
- **WHEN** 点检记录中某检查项判定异常
- **THEN** 系统提示并可一键生成缺陷 Issue 草稿

### Requirement: 缺陷经 Issue 闭环
设备缺陷 SHALL 以 Issue 外壳＋设备扩展记录表示，并沿 Issue 状态闭环。

#### Scenario: 缺陷登记
- **WHEN** 登记一条设备缺陷
- **THEN** 产生关联该设备的 Issue 与缺陷扩展记录

### Requirement: 维修复用 WorkItem
缺陷维修 SHALL 生成 MAINTENANCE 类型 WorkItem，可附加可选维修模板检查清单；维修完成后缺陷进入待验证，验证通过关闭。

#### Scenario: 维修到关闭
- **WHEN** 维修工作完成且验证人确认
- **THEN** 缺陷 Issue 关闭，设备时间轴显示缺陷、维修与验证

### Requirement: 设备关联时间轴
设备详情 SHALL 展示其点检、观察、缺陷、维修、DCS 报警和相关生产工作的时间轴。

#### Scenario: 查看设备时间轴
- **WHEN** 打开某泵的详情
- **THEN** 时间轴中可见最近一次报警与由此产生的缺陷
