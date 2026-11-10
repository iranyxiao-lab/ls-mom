# 通用列表（SchemaTable）

台账列表由 ListSchema 驱动：列、筛选、排序、分页只接受服务端白名单里的字段；行与批量动作只绑定已发布的 Operation。表格用 vxe-table 实现，封装在 `components/SchemaTable` 里，业务页面不直接使用 vxe 的 API。

## 列设置

工具栏右侧「列设置」：

| 操作 | 方式 |
| --- | --- |
| 调整顺序 | 拖动表头，或在列设置里点上移、下移（键盘可用） |
| 调整宽度 | 拖动表头右边缘 |
| 显示、隐藏 | 列设置里勾选 |
| 固定到左侧 | 列设置里点「固定」，再点取消 |
| 恢复默认 | 列设置底部「恢复默认」 |

勾选列、状态列和操作列的位置固定，不能拖动。

列设置的状态是 `ColumnPref[]`（`{ code, visible, width?, fixed? }`，按顺序）。SchemaTable 不自己保存：每次修改发出 `columns-change`，调用方（保存视图）存起来，下次通过 `columns` 传回。传回的设置里有已弃用或不再开放的字段时自动忽略，并发出 `columns-dropped`（字段编码列表）让调用方提示用户；新增的字段按默认顺序追加在末尾。纯函数在 `columnState.ts`。

## 筛选

搜索栏从左到右：

| 控件 | 说明 |
| --- | --- |
| 关键字 | 一次搜索所有开放查询的文本字段（`_keyword`，服务端 ILIKE，通配符转义）；回车或点搜索 |
| 快速筛选芯片 | 「本组织」（`orgId = 当前组织`）、「我创建的」、「未完成」（状态不在终态）、「草稿」；只在台账有对应字段或状态机时显示，可组合 |
| 高级筛选 | 打开抽屉，按字段逐个设置条件，点「搜索」应用并关闭 |

当前生效的条件在搜索栏下方显示为标签（如「严重程度 等于 中」「关键字：泵」），点标签上的 × 删除单个条件，「清空」删除全部。

芯片里的"当前用户"以变量保存（`$context.orgId`、`$context.partyId`），查询前用 `/api/ui/form-context` 解析成本人的组织、人员，所以共享出去的视图对每个人都指向他自己的组织。

枚举字段的中文名来自 Concept 字段的 `props.optionLabels`，列表单元格、筛选下拉和条件标签共用；新增枚举字段时一并填写，否则显示原始编码。

## 保存视图

「保存视图」把当前的筛选、关键字、列设置、排序和分组存下来：

- 个人视图只有自己能看到；有 `lsmom:ui:view:share` 权限的用户可以把视图共享给一个角色，该角色的用户都能选用，只有创建者能修改、删除。
- 每个台账可设一个默认视图（保存时勾选，或选中后点「设为默认」），打开列表时自动应用。
- 保存时服务端校验字段必须属于该台账；字段后来弃用的，套用视图时忽略并提示「X 已停用，已忽略」，其余条件照常生效。
- 限制：每人每台账 50 个视图，单个定义不超过 32 KB，筛选 50 条、排序 5 条。

接口：`/api/ui/saved-views`（GET `?concept=`、POST、PUT `/{id}`、DELETE `/{id}`、PUT `/default`、GET `/roles`）。读取、保存某台账的视图需要该台账的列表权限。表：`ui_saved_view`、`ui_saved_view_default`（V206）。

## 抽屉详情

点击一行在右侧抽屉打开详情，不离开列表：

- 抽屉头部：记录名、在当前页中的位置（如 2 / 20）、「上一条」「下一条」、「在新页签打开」。
- 内容：状态（状态机的显示名）、当前状态下本人可执行的动作、按记录冻结版本只读显示的表单、时间轴（新的在前）。
- 动作需要填写的输入（如驳回意见）在抽屉内展开填写；需要选人等复杂输入的动作（输入里有必填的整数编号）不在抽屉显示，到对应工作台办理。
- 在抽屉里办理动作、切换上一条/下一条时列表不重新查询，当前行高亮跟随；关闭抽屉后如果办理过动作再刷新列表。
- 拖动抽屉左边缘调整宽度，宽度记在本机（`ls:ledger-detail-width`），下次打开沿用。
- 「在新页签打开」进入 `/lsmom/record/<Concept>/<id>`，与抽屉共用 `RecordView` 组件。

动作完成但服务端返回提醒（如资源时间冲突）时，提示写成"已XX。注意：……"，不会只显示提醒让人以为没有执行（`utils/opResult.ts` 的 `notifyDone`）。

## 行内编辑与行内动作

**行内编辑**只对草稿（状态机的初始状态）开放，且字段可写（读写权限）、类型是文本、数字、日期或枚举；Concept 字段 `props.inlineEdit: false` 可关闭。可编辑的单元格带虚线下划线：

- 双击进入编辑，Enter 或失焦保存，Esc 取消；单击仍打开详情（可编辑单元格会稍等一下，判断是不是双击）。
- 保存走通用编辑接口（带版本号），服务端照常校验草稿状态、字段策略和表单规则；保存中、已保存（2 秒后消失）、未保存（悬停看原因）就近标在单元格里，被拒时保留原值。

**行内动作**只显示当前状态下本人可执行的动作（不可执行的不显示，不置灰）：前 2 个直接显示（草稿的「编辑」算一个），其余收进「更多」。需要填写输入的动作点击后在详情抽屉里打开该动作的输入。

## 批量操作

勾选后列表底部出现批量操作栏：已选条数、可批量执行的动作（不需要输入的迁移动作）、导出所选、取消选择。

- 勾满本页且筛选结果多于本页时，可「选择全部 N 条筛选结果」，需要二次确认（一次最多 5000 条）；之后的批量动作、导出都针对全部筛选结果。
- 批量动作逐条独立执行（每条独立校验、独立事务，4 条并发），超过 50 条或跨页全选时先确认条数。当前状态不允许的记录直接记为失败、不发请求。
- 结果对话框显示进度、成功与失败条数和每条失败原因；执行中可以「后台继续」关闭对话框，进度留在操作栏。「重试失败项」先取最新版本与状态，只重新执行失败的记录。
- 导出所选：全部筛选结果按当前条件导出，本页勾选按编号（`id in`）导出，都走异步导出任务。

## 有 N 条更新

列表每 30 秒（`pollInterval`，0 关闭）按当前筛选条件检查有没有他人新增或修改的记录（`/aggregate` 统计 `updatedAt` 晚于加载时最新修改时间的条数，不占用列表查询），有则在列表上方提示「有 N 条更新」，不自动刷新，滚动位置和勾选不变；点「刷新查看」加载最新数据并恢复勾选和滚动位置。页面隐藏、正在加载或批量执行时不检查。新建记录的 `updated_at` 等于创建时间。

## 大数据量

- 超过 60 行时纵向虚拟滚动，只渲染可见行；表格最大高度默认 640px（`maxHeight`）。
- 每页条数可选 10、20、50、100、500。
- 首次加载显示骨架屏，之后刷新用表格自带的加载遮罩，不跳动。

单元测试用 1 万行验证：DOM 中渲染的行少于 120 行，连续滚动 80 帧期间没有超过 50ms 的长任务（`PerformanceObserver('longtask')`，无头 Chromium）。

## 测试选择器

- 表格：`[data-testid="table"]`；数据行：`.vxe-table--main-wrapper .vxe-body--row`。
- 固定列会在左右固定区各渲染一份，按表头查找时排除 `.fixed--hidden`。
- 列设置：`col-settings`、`col-visible-<字段>`、`col-fix-<字段>`、`col-reset`。
- 筛选：`keyword`、`chip-<myOrg|mine|open|draft>`、`adv-open`、抽屉 `adv-drawer` 内 `filter-<字段>`、`search`、`reset`；条件标签 `conds`、`cond-<字段>`、`cond-clear`。
- 抽屉详情：`ledger-detail` 内 `detail-title`、`detail-pos`、`detail-prev`、`detail-next`、`detail-newtab`、`detail-status`、`detail-op-<Operation>`、`detail-in-<输入>`、`detail-op-confirm`、`detail-timeline`；新页签 `record-page`、`record-view`。
- 行内编辑：`cell-<字段>-<id>`（可编辑单元格）、`cell-editor-<字段>-<id>`、`cell-state-<字段>-<id>`；行内动作 `row-edit-<id>`、`op-<Operation>-<id>`、`row-more-<id>`。
- 批量：`batch-bar`、`batch-count`、`batch-select-all`、`batch-<Operation>`、`batch-export`、`batch-clear`；对话框 `batch-dialog` 内 `batch-summary`、`batch-failed`、`batch-retry`、`batch-close`。
- 更新提示：`updates-banner`、`updates-refresh`。
- 视图：`view-select`、`view-save`、`view-default`、`view-delete`；保存对话框 `view-save-dialog` 内 `view-name`、`view-overwrite`、`view-as-default`、`view-role`、`view-save-confirm`。
