# 可操作的错误提示

业务错误（接口返回非 200 的业务编码）统一由 `showActionableError` 显示，内容分三层：

1. **发生了什么**：标题，用服务端给出的业务原因，如"郑师傅的动火作业资质已于 11 月 5 日过期"。
2. **为什么、怎么解决**：`details.why` 一句说明；`details.fix` 给出修复按钮。
3. **技术详情**：默认折叠，列出错误编码、检查项（守卫编码）和追踪编号（`X-Trace-Id`），方便反馈给管理员。不展示堆栈。

有修复按钮时提示不自动消失，等用户处理；没有时 6 秒后消失。调用方带 `silentError` 头时不弹，由调用方就近显示（如扫码结果）。

## 修复入口

`details.fix` 是数组，每项 `{ label, to? , action?, ...参数 }`：

- `to`：跳转页面，如 `/lsmom/ledger/ls.training.TrainingRecord?id=5`（台账带 `id` 时直接打开该记录）。
- `action`：交给当前页面处理。页面用 `registerFixAction(action, handler)` 登记，卸载时注销；当前页面没有登记的动作不显示按钮，避免点了没反应。`reload` 没有页面处理时刷新页面。

服务端没给说明时，常见错误编码有通用说明：版本冲突、状态已变化（"刷新后重试"），无权限（"请联系管理员开通"）。

## 已提供修复指引的守卫

| 守卫 | 原因示例 | 修复入口 |
|---|---|---|
| `ls.training.assigneeQualified`（培训包，附加到 `ls.core.Work.assign`） | 郑师傅的动火作业资质已于 11 月 5 日过期 / 李四没有动火作业资质（需要通过「动火作业安全」培训） | 改派他人（分派台：去掉该人员、光标回到人员选择）、查看培训记录 |
| `ls.core.Issue.evidenceRequired` | 严重度为 HIGH 的异常提交前必须上传现场证据 | 上传现场证据 |
| `ls.core.Issue.rectificationDone` | 还有 2 条整改工作未完成 | 查看整改工作 |
| `ls.core.Issue.verifierSeparation` | 验证人不能是唯一的整改执行人 | 请他人验证 |

## 分派资质守卫

培训课程新增字段「适用作业」（`workKeywords`，逗号分隔的关键词）。分派工作时，若工作标题含某门**已发布**课程的关键词，每个被分派的人员都必须在作业日期（计划开始日期，没有则为当天）持有该课程的有效培训记录（有效期至 ≥ 作业日期），否则拒绝分派。

拒绝时的 `details`：`partyId`、`partyName`、`courseCode`、`workOn`、`expiredOn`、`recordId`（最近一条记录）、`why`、`fix`。

## 测试

- 单元：`tests/unit/actionable-error.spec.ts`。
- 后端：`TrainingPackTest.分派资质守卫_…`。
- 端到端：`tests/e2e/qualification.e2e.mjs`（分派台被拒 → 改派他人 → 分派成功；查看培训记录打开过期记录）。

## 测试选择器

`.ls-error-notice`、`error-notice`、`error-why`、`error-fixes`、`error-fix-<action>`（跳转类为 `error-fix-link`）、`error-tech`。
