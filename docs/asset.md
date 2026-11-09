# 设备（标准 Pack `ls.asset`）

设备 / 工位台账、状态历史、点检计划与记录、缺陷登记、维修与验证关闭、设备时间轴。缺陷不另造闭环：外壳是核心 Issue，维修是 MAINTENANCE 类型的 WorkItem。

## 组成

| 部分 | 位置 |
|---|---|
| Pack 定义（Concept、状态机、视图、黄金场景） | `ls-mom-modules/ls-mom-biz/pack-asset/src/main/resources/lsmom-packs/ls.asset/` |
| 迁移效果、守卫、按周期生成点检、设备时间轴 | `pack-asset` 的 `com.lsmom.biz.asset` |
| 菜单与权限点（编号 3100–3199） | `pack-asset/src/main/resources/db/migration/core/V140__asset_menus.sql` |
| 演示角色授权 | `pack-asset/src/main/resources/db/seed/demo/V4__asset_role_menus.sql`（设备科、质量科数据域设为全部） |
| 前端页面 | `plus-ui/src/views/lsmom/asset/`（设备台账、点检、缺陷与维修） |

## 对象与状态

| Concept | 状态 / 操作 |
|---|---|
| `Asset` 设备 | 未投用 → `commission` 投用（运行）；`standby` 备用、`maintain` 停机检修（必填原因）、`stop` 停机（必填原因）、`run` 恢复运行、`scrap` 报废（必填原因）。每次变更写一条 `AssetStatusLog`（原状态、新状态、原因、操作人、时间） |
| `InspectionPlan` 点检计划（子表检查项） | `activate` 启用（至少一个检查项、设备未报废）、`pause` 暂停、`generate` 生成一次点检 |
| `InspectionRecord` 点检记录（子表结果） | `submit` 提交（逐项写入，读数超上下限自动判异常，每项一条 Observation，点检工作随之完成）、`raiseDefects` 异常项一键生成缺陷草稿 |
| `Defect` 设备缺陷 | 草稿 → `report` 登记 → `createRepair` 维修中 → `submitVerify` 待验证 → `verifyPass` 关闭 / `verifyFail` 回到维修中；`cancel` 取消 |

### 点检

- `generate`：生成一条 INSPECTION 工作（分派给计划责任人并占用该设备）和一条点检记录草稿（检查项从计划复制），下次点检日期顺延一个周期。同一计划同一应检日期不重复生成。工作与记录归责任人所在组织，班组能在自己的数据域里看到并填报。
- 定时任务每天 00:10（`lsmom.asset.inspection-cron`）为到期的生效计划以系统身份执行 `generate`，经网关留审计。

### 缺陷与维修

- 缺陷的外壳是 Issue（类型 `EQUIPMENT_DEFECT`，`ext_concept = ls.asset.Defect`，关联对象 `ASSET:<设备编号>`），两者状态同步推进。
- 点检生成的缺陷草稿带 Issue 草稿，并关联来源点检记录；手工登记的缺陷在 `report` 时创建 Issue。
- 严重度为高、严重的缺陷登记前必须上传现场证据（缺陷或 Issue 上用途为 EVIDENCE 的附件）。
- `createRepair`：Issue 进入整改中，按标题生成 MAINTENANCE 工作（来源指向 Issue），可附维修检查清单（写入工作描述）、指定维修人和期限；验证不通过后可以再派。
- `submitVerify` 要求维修工作全部完成；约束 Profile 要求分离时，验证人不能是唯一的维修执行人。

## 设备时间轴

`GET /api/asset/assets/{id}/timeline` 按时间倒序返回：状态变更、点检、异常 / DCS 观察、DCS 报警（来源 DCS、关联该设备的 Issue）、缺陷登记 / 维修 / 验证（缺陷的领域事件）、维修工作与占用该设备的其他工作。设备不在调用者数据域内时，设备负责人和被派过该设备点检的人也可查看。

其他只读接口：`GET /api/asset/inspections/mine`（我的点检）、`GET /api/asset/defects/{id}/repairs`（缺陷的维修工作）、`GET /api/asset/orgs`（可选所属组织）。

## 验证

- 后端：`AssetPackTest`（安装与黄金场景、状态留痕、计划生成与定时任务、超限判异常、一键生成缺陷、缺陷 Issue 外壳、维修到验证关闭、时间轴、验证人分离、取消）。
- 前端：`tests/unit/asset.spec.ts`；端到端：`tests/e2e/asset-ops.e2e.mjs`（设备员、班长两个演示账号走完整闭环）。
