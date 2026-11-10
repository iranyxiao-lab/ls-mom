# 新手引导与上下文帮助

## 分步引导

协同工作中心（`/lsmom/workbench`）、表单设计器（`/lsmom/form-designer`）、移动报工（`/production/report`）首次进入时自动出现分步引导（driver.js 1.10.2，按需加载，不进首屏包）。

- 每步有「跳过」「上一步」「下一步」和进度（如 `1 / 4`），最后一步为「知道了」。
- 看完记为 `done`；点「跳过」、关闭按钮、按 Esc 或点遮罩记为 `skipped`。两者都不再自动出现。
- 状态存在服务端偏好（`tourWorkbench`、`tourDesigner`、`tourMobileReport`），换浏览器登录后保持。写 `pending` 可让引导重新自动出现。
- 顶栏「帮助」（问号图标）→「重看本页引导」随时重新打开。

引导定义在 `src/guide/tours.ts`：每步用页面上的 `data-tour` 锚点定位（锚点不在页面上时居中显示）。新增页面引导时在这里加一项，并在页面上加锚点、在 `PrefService.PREFS` 里登记偏好键。

## 操作手册（SOP）

「帮助」→「操作手册」打开知识中心，并把本页主题填进提问框（如"协同工作中心 待办办理 怎么操作"），由知识库里已发布的 SOP 文档回答并附引用。没有引导的页面打开知识中心首页。

## 字段悬浮说明

表单字段配置了说明（设计器里字段的「说明」，即 `help`）时，标签旁显示一个问号图标：鼠标移上或键盘聚焦时显示说明。说明不再常驻在字段下方，表单更紧凑。

## 测试

- 单元：`tests/unit/tour.spec.ts`、`tests/unit/schema-form.spec.ts`（字段说明）。
- 后端：`PrefServiceTest.新手引导状态_看完或跳过后记住`。
- 界面：`tests/ui/app/tour.spec.ts`。测试登录助手登录后统一把引导标为已跳过，避免引导遮住页面；引导用例自己写 `pending` 重置。

## 测试选择器

`help-open`、`help-tour`、`help-sop`、`tour-popover`、`tour-next`、`tour-skip`、`help-<字段>`。
