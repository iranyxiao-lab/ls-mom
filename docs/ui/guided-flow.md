# 引导式流程

## 单据头部：状态与下一步

有流程的单据（详情抽屉、新页签详情页）顶部显示 `FlowHeader`（`components/LsFlow`）：

| 谁在看 | 显示 |
| --- | --- |
| 等待他人（如发起人） | 单据状态、当前节点、"等待 张三（车间主任）审核"、剩余时间（超时标红、24 小时内到期标黄）；发起人有「催办」 |
| 轮到自己 | "轮到你审核：部门审核"、剩余时间、直接可见的意见框、唯一主按钮（审核通过 / 确认 / 提交；被退回时为「再提交」），审核节点另有「退回发起人」「驳回」（驳回需确认，退回、驳回都要写原因） |
| 流程已结束 | "流程已结束：审批通过 / 已驳回 / 已撤回" |

节点要求填写的字段（节点字段策略的 `required`）如果都是文本，就在头部直接填写并随办理一起提交；有其他类型的字段时主按钮不可用，提示"需要填写更多内容，打开办理面板"，在 `TaskPanel` 抽屉里按节点策略填写。

被退回且尚未再提交时，顶部醒目显示"被 张三（车间主任）退回（部门审核）"和退回意见。

## 流程进度

头部下方的步骤条按流程配置顺序列出"发起"和各人工节点（含分支里的节点）：已完成、进行中、已退回 / 已驳回（红色）、未开始。点击节点名查看该节点每次办理的人、时间、结果和意见；进行中的节点显示当前办理人。

## 接口

`GET /api/workbench/biz/{concept}/{id}/progress` 返回最近一次流程的 `flow`（无流程时为 null）、`steps`、`current`（办理人姓名与第一个角色名、`dueAt`、`remainingMinutes`、`mine`）、`myTask`、`canUrge`、`returned`。

能查看的人：发起人、任一节点的办理人或候选人、超级管理员；其他人需要该台账的查询权限且记录在本人数据范围内。原有的 `GET /api/flow/biz/{concept}/{id}` 也按同一规则校验（之前没有校验）。

## 测试选择器

`flow-header`、`flow-status`、`flow-waiting`、`flow-urge`、`flow-my-turn`、`flow-act`、`flow-field-<字段>`、`flow-comment`、`flow-main-action`、`flow-return`、`flow-reject`、`flow-open-task`、`flow-returned`、`flow-ended`、`flow-steps`、`flow-step-<节点>`、`flow-step-btn-<节点>`、`flow-step-detail-<节点>`。
