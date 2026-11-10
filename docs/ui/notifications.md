# 通知抽屉

- **入口**：顶栏铃铛（显示未读总数，每分钟刷新）→ 右侧抽屉，分「待办提醒 / 系统消息 / @我」三组，每组有未读数和「全部已读」。
- **数据**：`biz_flow_notice`（flow 模块），接口 `GET /api/workbench/notifications`，已读 `POST /api/workbench/messages/read`。
  分组：待办提醒 = ACTION、REMIND、URGE、ESCALATE、EXCEPTION、TRANSFER、PACK_REVIEW、DCS_ALARM；系统消息 = SYSTEM（以及 SSE 推送的公告）；@我 = MENTION、CC。
- **发通知**：业务 Pack 用 `com.lsmom.flow.notice.Notices`：
  `notify(kind, parties, bizType, bizId, title, message, link, action, by)`；`nearestMembers(orgUnitId)` 取组织（或最近一级有人员的上级组织）的人员。
- **抽屉内动作**：通知带 `action {operation, concept, targetId, label}` 时显示按钮，点击后按对象当前版本调用该 Operation（权限与守卫照常校验）。
  对象办结后调用 `Notices.resolve(bizType, bizId)`，所有人的同一条待确认通知显示「已处理」。
- **示例**：交接班确认交班后，接班班组收到「交接班待确认」（链接 `/production/handover?id=`，动作「确认接班」）；接班或退回后通知标记为已处理。
- **测试**：`ProductionPackTest`（通知生成与办结），`tests/ui/app/notifications.spec.ts`（从通知确认交接班）。
