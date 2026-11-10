# 分级确认与撤销

动作按能否撤销分两类处理：

| 类别 | 例子 | 界面行为 |
|---|---|---|
| 低风险、可逆 | 调整班次（看板拖动、分派台）、隐藏首页卡片、保存 | 立即执行，不弹确认框；提示里 5 秒内可点「撤销」 |
| 不可逆或高风险 | 提交、驳回、关闭不符合项、关闭缺陷、取消、批量动作 | 先弹确认框，列出影响对象和后果，确认后执行 |

能否撤销由服务端决定：Operation 契约 `meta_operation.undo_operation` 登记了补偿 Operation 的动作才可撤销，其余动作在通用台账、记录详情、不符合项和缺陷看板上都先确认。

## 撤销

- 状态机迁移在 Pack 里声明 `undoOperation`（必须是同一状态机里能回到原状态的迁移，发布时校验）。效果用 `ctx.undoWith(执行前的值)` 登记撤销输入。
- 执行结果带 `undo: { operation, targetId, input }`，撤销时以结果里的 `version` 对同一对象执行补偿 Operation。撤销也是一次正常的 Operation：同样校验权限、数据范围、版本和守卫，也记入时间轴。
- 前端 `useUndoable().run({ done, apply, revert, exec, compensate, after })`：`apply` 先改界面（乐观更新），执行失败时 `revert` 恢复；成功后提示里给出「撤销」，只能点一次，5 秒后消失。服务端没给 `undo` 时只显示完成提示。
- 纯界面上的可逆操作（如首页编辑时隐藏卡片）用 `useUndoable().offer(文字, 撤销函数)`。

目前登记了补偿 Operation 的动作：`ls.production.ShiftPlan.move`（调整班次，以调整前的班组、日期、班次再调整一次）。

## 确认框

`confirmOperation({ name, label, impact, stateLabel })` 先取 `GET /api/op/impact?operation=&targetId=`，再弹框列出：

- 业务模块给出的影响对象（`OperationImpactProvider`），如"将关闭 1 个不符合项：焊缝气孔""同时关闭关联问题 IS-0012""关联整改工作 2 项（已完成 2 项）"；没有时显示对象名称。
- 状态变化："状态：待验证 → 已关闭"。
- 进入终态时："关闭后流程结束，不能再编辑或办理"。
- 最后一行固定为"此操作不能撤销"。

确认按钮文字就是动作名；取消则什么都不执行。批量动作沿用批量栏自己的确认（说明条数）。

`/api/op/impact` 只读，目标对象须在本人数据范围内。已提供影响说明的动作：问题的验证通过 / 驳回 / 取消，不符合项的关闭 / 驳回，缺陷的验证通过 / 取消。新模块实现 `OperationImpactProvider`（Issue 承载的对象可直接用 `IssueImpact`）。

`/schema` 返回的动作和 `/api/op/allowed` 都带 `undoable`，界面据此选择撤销还是确认。

## 测试选择器

`undo-text`、`undo-btn`；确认框 `.ls-op-confirm-box`，内容 `op-confirm-body`。
