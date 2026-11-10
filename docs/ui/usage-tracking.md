# 使用埋点与放弃点报表

用于发现高成本流程：哪些关键任务做得慢、在哪一步放弃、出错多少次。对应 `modernize-ui-ux` 任务 2.5。

## 隐私说明（对最终用户披露）

- **匿名**：埋点表 `ui_usage_event` 没有用户、账号、IP、组织等字段，无法追溯到个人。
- **不记内容**：只记录任务名（如 `nc.register`）、步骤名（如 `asset`）、事件类型（开始／步骤／完成／放弃／出错）和耗时。表单里填写的任何内容都不上报。
  - 前端 `track()` 只取这四个字段，调用方多传的字段不会被发送。
  - 任务名和步骤名必须是小写标识符（`^[a-z][a-z0-9_.-]{0,63}$`），自由文本在格式上就无法进入。服务端用同一规则再校验一次。
- **可关闭**：管理员在「系统监控 → 体验分析」关闭后：
  - 前端停止上报（最长 5 分钟内生效，刷新页面立即生效）；
  - 服务端拒收所有上报。
- **保留期**：180 天，每天凌晨自动清理。

## 开发者：给关键任务加埋点

```ts
import { useTaskTracking } from '@/utils/track';

const visible = ref(false); // 对话框或抽屉的显示状态
const tracker = useTaskTracking('nc.register', visible);
```

1. 在表单容器上绑定 `@focusin="tracker.onFocusin"` 和 `@pointerdown="tracker.onFocusin"`。
2. 给每个表单项加 `data-track-step="asset"`，值用字段名，不要用显示文字。
3. 提交成功后、关闭对话框之前调用 `tracker.complete()`。
4. 校验失败或保存失败时调用 `tracker.error('字段名')`。

记录规则：

- `visible` 变为 `true` 时记一次开始。
- 未完成就关闭对话框或离开页面，记为在最后到达的步骤放弃。离开页面时剩余事件用 `keepalive` 立即发出，不会丢。
- 新任务要在 `src/api/lsmom/uiUsageModel.ts` 中登记显示名（`TASK_LABELS`、`STEP_LABELS`）。

## 报表

接口为 `GET /api/ui/usage/report?days=30`，需要权限 `lsmom:ui:usage`。每个任务返回：

- 开始、完成、放弃、出错次数；
- 完成耗时中位数；
- 各步骤的放弃次数和放弃率（放弃次数 ÷ 开始次数）。

某步骤放弃率 ≥ 30%、且该任务开始次数 ≥ 5 时，标为**高放弃率**。

开关接口为 `PUT /api/ui/usage/config`，需要权限 `lsmom:ui:usage:manage`。

## 验证

- **后端**：`UsageServiceTest`，覆盖：
  - 自由文本和多余内容不入库；
  - 关闭后拒收；
  - 单批上限；
  - 高放弃率阈值；
  - 样本不足不标记。
- **前端单测**：`tests/unit/track.spec.ts`，覆盖：
  - 只发白名单字段；
  - 关闭后不上报；
  - 在「选择设备」取消时记为该步放弃，上报中不含填写内容；
  - 报表页的高放弃率标记与开关。
- **端到端**：`tests/e2e/ui-usage.e2e.mjs`，在部署环境中以演示账号真实操作，覆盖：
  - 上报内容；
  - 卸载时送达；
  - 报表标记；
  - 越权；
  - 关闭与恢复。
