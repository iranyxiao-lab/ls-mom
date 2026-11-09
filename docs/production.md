# 轻生产（标准 Pack `ls.production`）

不需要 BOM、工艺路线和仓库，也能走完"需求 → 工作 → 分派 → 填报 → 确认 → 交接班 / 看板"。

## 组成

| 部分 | 位置 |
|---|---|
| Pack 定义（Concept、状态机、视图、指标、黄金场景） | `ls-mom-modules/ls-mom-biz/pack-production/src/main/resources/lsmom-packs/ls.production/` |
| 迁移效果与守卫（需求转工作、班次调整、交接班） | `pack-production` 的 `com.lsmom.biz.production` |
| 菜单与权限点（编号 3000–3099） | `pack-production/src/main/resources/db/migration/core/V130__production_menus.sql` |
| 演示角色授权 | `pack-production/src/main/resources/db/seed/demo/V2__…`、`V3__…` |
| 前端页面 | `plus-ui/src/views/lsmom/production/`（需求板、工作板、分派台、移动填报、交接班、经营看板） |

应用启动时 `StandardPacks` 扫描类路径 `lsmom-packs/*/pack.yaml`，按依赖顺序安装或升级标准 Pack；内容没变就跳过，已停用的 Pack 不动，安装失败只记日志（应用中心可见），不阻止启动。设 `lsmom.pack.standard.enabled=false` 可关闭。

## 对象

| Concept | 说明 |
|---|---|
| `ProductionRequest` 生产需求 | 月 / 周 / 日 / 临时，只需描述；可选数量、单位、期望日期。`convert` 转为一条或多条 PRODUCTION 工作（可多次追加） |
| `ProductionContext` 生产上下文 | 工作的来源需求、计划数量、单位、产线 |
| `ShiftPlan` 班次安排 | 工作排到哪个班组、哪天、哪个班次。`activate` 生效、`move` 调整；同步改工作与分派的计划时间，资源冲突按约束 Profile 提示（宽松）或阻断（严格） |
| `ProductionOutput` 产出记录 | 数量、单位、时间、人员、批次、说明 |
| `MaterialRecord` 物料记录 | 领用 / 投料 / 退料 / 转移；**只记录事实，不核销库存，不校验物料平衡** |
| `ShiftHandover` 交接班（含子表交接事项） | 见下 |

班次时段：夜班 00:00–08:00、早班 08:00–16:00、中班 16:00–24:00（与字典 `ls_shift` 一致）。

## 交接班

1. 交班人新建交接班并 `generate`：汇总该班组该班次的已完成 / 未完成工作、班组未关闭异常、DCS 摘要，生成摘要草稿与交接事项。
2. 草稿**只有交班人自己看得到**（列表、详情、引用都不返回，按"不存在"处理）——由 `DraftPrivacy` 实现，不受数据域放宽影响；也只有交班人能改写或确认（守卫 `ls.production.handoverOwner`）。
3. 交班人 `publish`（交班确认，可在确认时修改摘要）后，接班班组可见。
4. 接班班组或其上级组织的人员 `accept`（守卫 `ls.production.handoverReceiver`：不能是交班人本人）。未完成事项移到接班班组的**下一班次**，出现在其工作板上；移入的时段与其他工作冲突时只提示，不阻止交接。也可 `returnBack` 退回交班人（回到草稿）。

## 权限

Operation 权限：`ls:production:request`、`ls:production:schedule`、`ls:production:handover`；工作执行沿用 `lsmom:work:*`；台账读写为 `crud:<Concept>:list|query|add|edit|remove`。
操作工通常是"仅本人"数据域：可以办理**分派给自己**的工作（网关按有效分派放行），不能办理同组织里别人的工作。
现场照片上传需要 `system:oss:upload`。

## 移动填报

手机打开"移动填报"：只列分派给自己的未完成工作 → 开始 → 填进度、人工、产出、说明 → 拍照。
"保存草稿"写服务器填报草稿并在本机暂存表单；"提交填报"后照片作为 `EVIDENCE` 附件挂到该次填报（`ls.core.WorkReport`）；进度 100% 时可"完工，提交确认"。

## 经营看板

Pack 视图 `ls.production.opsDashboard`，指标全部已注册、可钻取：

| 指标 | 口径 |
|---|---|
| `ls.production.workload` | 生产工作数（不含已取消），按创建时间 |
| `ls.production.onTimeRate` | 已完成且有计划结束的生产工作中按期完成的比例，按计划结束时间 |
| `ls.core.issueCount` | 异常数（内置） |
| `ls.production.outputQty` | 产出记录数量合计，按记录时间、可按单位分组 |
| `ls.production.laborMinutes` | 已提交填报的人工分钟 |
| `ls.production.equipmentBusy` | 当前分派到设备的工作数，按设备分组 |

## 验证

- `SliceAGoldenScenariosTest`：数据集 `golden/slice-a.yaml` 驱动 DOM-01（无制造主数据完整闭环）、DOM-02（宽松提示 / 严格阻断与严格模式填报要求）、DOM-03（不核销 + 交接班双方确认）。
- `ProductionPackTest`：标准 Pack 安装、需求转工作、班次调整冲突、交接班草稿不可见与双方确认。
- `plus-ui/tests/e2e/production-ops.e2e.mjs`：演示账号在部署环境里走完全流程，其中移动填报使用无头 Chromium 的手机视口与触屏模拟。
