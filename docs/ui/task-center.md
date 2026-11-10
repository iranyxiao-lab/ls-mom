# 任务中心

导入、导出、批量操作、Pack 发布等耗时任务在后台执行。发起后可以离开页面，完成时右上角弹出通知（"导入：课程 已完成 · 已导入 5000 条"），点通知打开任务中心。

## 入口

顶栏「任务中心」图标：有进行中的任务时图标转动并显示数量。抽屉列出本人最近 20 个任务（进行中的在前），每项显示进度、结果摘要和可做的事：

| 任务 | 来源 | 完成后 |
|---|---|---|
| 导入检查 `IMPORT_CHECK` | 台账「导入」上传文件 | 错误行报告（行号、字段、原因，最多显示 100 条）；没有错误时可「确认导入」 |
| 导入 `IMPORT` | 确认导入 | 已导入条数，「查看台账」 |
| 导出 `EXPORT` | 台账「导出」、批量栏「导出所选」 | 「下载」 |
| Pack 发布 `PACK_INSTALL` | 应用中心「发布」 | 发布的版本；失败时显示门禁原因 |
| 批量操作 `BATCH` | 列表批量栏 | 成功、失败条数（在浏览器里执行，切换页面不中断；关闭或刷新浏览器会中断） |

## 通知规则

- 发起页面还开着（如导入对话框未关）时，结果直接显示在页面上，不再弹通知。
- 关掉对话框或离开页面后完成：弹出通知，并在服务端记为已通知（`notified_at`），刷新或换浏览器登录不会重复弹。
- 离线期间完成的任务（最近 24 小时内）下次登录时补发通知。
- 只能看到自己发起的任务。

## 接口

- `GET /api/tasks?limit=20`、`GET /api/tasks/{id}`、`POST /api/tasks/{id}/notified`
- `POST /api/crud/{concept}/import/async`（返回任务编号，结果含预检报告）、`POST /api/crud/{concept}/import/{batchId}/confirm/async`
- `POST /api/changesets/{id}/release/async`
- 导出接口不变，提交时自动登记任务。

服务端：`BackgroundTasks`（表 `core_background_task`），进度写入用独立事务，任务失败也保留记录。前端：`src/utils/taskCenter.ts`（轮询、跟进、通知）、`src/layout/components/TaskCenter`（顶栏图标；抽屉首次打开时才加载）。

## 测试

- 后端：`ImportExportTest.后台导入_任务中心记录进度与结果_仅发起人可见`。
- 单元：`tests/unit/task-center.spec.ts`。
- 端到端：`tests/e2e/task-center.e2e.mjs`（5000 行导入：检查中离开页面收到通知 → 任务中心错误行报告 → 改好后确认导入、离开页面收到"已导入 5000 条" → 导出完成通知并下载）。

## 测试选择器

`tasks-open`、`tasks-running`、`task-drawer`、`task-<id>`（带 `data-kind`、`data-status`）、`task-summary`、`task-errors`、`task-confirm-import`、`task-download`、`import-progress`、`.ls-task-notice`。
