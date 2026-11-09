# 表单设计器与 fc-adapter

可视化设计器使用 [form-create designer](https://github.com/xaboy/form-create-designer)（Vue 3＋Element Plus），但**产品只存储 LS FormSchema**（见 [form-schema.md](form-schema.md)）。设计器是可替换的编辑器，中间由 `fc-adapter` 双向转换：

```
LS FormSchema ──toFcRule──▶ form-create 规则 + options ──(设计器编辑)──▶ fromFcRule ──▶ LS FormSchema ──▶ 后端校验/发布
```

运行时不使用 form-create：录入、详情、打印、移动端都由 LS 自己的渲染器（`SchemaForm`、`SchemaDetail`、`SchemaPrint`、`SchemaMobile`）直接渲染 LS Schema。设计器预览也使用这些运行时渲染器，所见即所得。

## 版本冻结

| 包 | 版本 | 方式 |
| --- | --- | --- |
| `@form-create/designer` | 3.5.0 | `package.json` 精确版本 |
| `@form-create/element-ui` | 3.3.4 | `package.json` 精确版本 |
| `@form-create/core`、`@form-create/utils`、`@form-create/component-*` | 3.3.4 / 3.3.0 | `pnpm-workspace.yaml` 的 `overrides` 固定间接依赖 |
| `@babel/runtime`、`@babel/runtime-corejs3` | 7.29.10 | 同上 |

`core-js-pure` 的安装脚本在 `allowBuilds` 中禁用。升级设计器必须同时：改精确版本与 overrides、重跑 `tests/unit/fc-adapter.spec.ts`（含真实设计器往返）与 `tests/unit/form-designer.spec.ts`、重跑设计器 E2E，并在 `docs/upstream-patches.md` 登记。

**唯一引入点**是 `src/vendor/form-create/index.ts`；业务代码只经它和 `src/utils/lsform/fcAdapter.ts` 使用设计器，不修改设计器源码。设计器插件（及其需要的 Element Plus 全量注册）只在打开设计器页面时由 `installDesigner` 安装，不进入其他页面的首屏包。

## 映射规则

| LS | form-create |
| --- | --- |
| `fields[]` + 布局中的 `field` 节点 | 一个字段规则：`field` = 字段键，`title` = 标签，`$required`，`props`（`maxlength`、`min`、`max`、`precision`、`multiple`……），`options` |
| `widget` | 规则 `type`（`input`、`inputNumber`、`datePicker`、`select`……）；LS 专有控件（业务引用、证据、计算字段、注册组件）落为 `input`，真实控件写在 `lsExt.widget` |
| `ref`、`computed`、`dictType`、`pattern`、`readonly`、`hidden`、`component`、`help`…… | 规则的 `lsExt`（设计器原样保留，不解释） |
| `subTables[]` + 布局中的 `subTable` 节点 | `tableForm` 规则，`field` = 子表键，列保存在 `props.columns[{label, rule:[字段规则]}]`；`lsExt.conceptCode` 为子 Concept |
| `group` / `panel` | `elCard`（`lsExt.node` 区分） |
| `row` / `col`、`tabs` / `tab`、`collapse` / `panel` | `fcRow`/`col`、`elTabs`/`elTabPane`、`elCollapse`/`elCollapseItem` |
| `divider`、`text`、`description` | `elDivider`、原生 `div`（`lsExt.node` 标记） |
| `metricCard`、`relatedSummary`、`relatedList` | 占位 `div`，属性在 `lsExt` |
| 未放进布局的字段 | 规则带 `lsExt.unplaced`，转回时不进入布局 |
| 表单级：`formCode`、`version`、`conceptCode`、`title`、`rules`、`actions`、`nodePolicies`、`migration` | `options.lsMeta`；`labelWidth` ↔ `options.form.labelWidth`（默认 100px，转回时省略默认值） |

设计器内部键（`_fc_id`、`name`、`display`、`hidden`、`_fc_drag_tag`……）在转回时丢弃。

## 单向边界

以下 form-create 能力**不能**转回 LS Schema。`fromFcRule` 遇到时抛出 `FcAdapterError`，列出每处的路径与原因；工作区把它显示为错误并阻止保存：

| 能力 | 原因 |
| --- | --- |
| 事件 `on`、钩子 `hook`、`effect.fetch` | 可执行逻辑；LS 的联动只能写成受限 CEL 规则 |
| 联动 `control` | 同上，改用规则或节点策略 |
| form-create 的 `computed` | 用 LS 的 `computed` 控件（受限 CEL，服务端重算） |
| 任何值为函数的属性 | 不可序列化，也是脚本 |
| `style`、`class`、原生 HTML（`native`） | 不允许自由样式与 HTML；展示用 `text`/`description` 节点 |
| 未知组件类型 | 只允许 LS 控件面板中的控件与上表中的布局组件 |

反方向（LS → form-create）没有丢失：所有 LS 属性都能放进规则或 `lsMeta`，因此 `LS → FC → LS` 等价（`normalizeForm` 后逐字段相等），由 `fc-adapter.spec.ts` 保证（含一次真实设计器 `setRule` / `getRule` 往返）。

即使绕过界面直接提交 Schema，后端仍会做结构、语义与脚本扫描（`FORM_SCRIPT_FORBIDDEN`），适配层不是唯一防线。

## 控件面板

工作区隐藏了设计器自带的「基础」菜单与不支持的组件，只提供 LS 面板（`lsDragRules.ts`）：

- 基础、选择、业务引用、证据、展示、高级六组 LS 控件，拖入后带 `lsExt.widget`；
- 子表单组件：「表格表单」（`tableForm`），用作子表；
- 辅助组件与布局组件：文字、分割线、栅格、标签页、卡片、折叠面板。

## 扩展面板

设计器右侧的 LS 扩展面板编辑设计器不理解的部分：

- **字段**：绑定 Concept 字段（主表控件只列主 Concept 字段，子表列只列所属子 Concept 的字段）、控件类型、只读/隐藏、正则、引用 Concept 与过滤、计算表达式、字典；
- **子表**：选中表格表单时绑定子 Concept，键自动取子 Concept 名首字母小写；
- **规则**：L1/L2/L3、`when`、`assert`、提示与挂载字段；
- **动作**：只能从本 Concept 已发布的 Operation 中选择；
- **节点策略**：按流程节点设置 `readonly`/`hidden`/`editable`/`required`；
- **表单**：标题、迁移策略与说明。

## 工作区

| 区域 | 说明 |
| --- | --- |
| 设计 | form-create 画布＋LS 扩展面板 |
| Schema | 当前 LS Schema（可手工编辑后「应用到设计器」，同样经过适配与校验）和与已发布版本的逐行差异 |
| 预览 | 桌面、移动、详情、打印四种运行时渲染 |
| 权限模拟 | 选择流程节点与角色权限，按字段策略算法显示可见/可编辑/必填 |
| 工具栏 | 撤销/重做（至少 100 步历史）、保存草稿、校验、发布；问题列表可点击定位到字段 |

路由：`/lsmom/form-designer/:code?concept=<Concept 编码>`。已发布的表单打开时自动派生下一版本草稿；新表单按 Concept 主表字段生成初稿，子表由实施人员拖入并绑定。

## 示例：适配层往返用的最小主子表单

```json lsview
{
  "viewType": "FORM",
  "formCode": "acme.tool.loanQuick",
  "version": 1,
  "conceptCode": "acme.tool.LoanOrder",
  "title": "快速借用",
  "layout": [
    { "type": "field", "key": "loanNo" },
    { "type": "field", "key": "borrower" },
    { "type": "field", "key": "loanDate" },
    { "type": "subTable", "key": "loanLine" }
  ],
  "fields": [
    { "key": "loanNo", "label": "借用单号", "widget": "input", "required": true },
    { "key": "borrower", "label": "借用人", "widget": "input" },
    { "key": "loanDate", "label": "借出日期", "widget": "date", "required": true }
  ],
  "subTables": [
    { "key": "loanLine", "label": "借出明细", "conceptCode": "acme.tool.LoanLine", "fields": [
      { "key": "tool", "label": "工器具", "widget": "input", "required": true },
      { "key": "qty", "label": "数量", "widget": "number", "min": 1 }
    ] }
  ]
}
```

## 测试

| 测试 | 内容 |
| --- | --- |
| `tests/unit/fc-adapter.spec.ts` | LS→FC→LS 等价、单向边界报错、真实设计器往返 |
| `tests/unit/form-designer.spec.ts` | 扩展面板、子表列绑定范围、Schema 区编辑与定位、差异、预览、权限模拟、≥50 步撤销重做、发布 |
| `tests/e2e/form-designer.e2e.mjs` | 部署环境无头浏览器：拖入表格表单→绑定子 Concept→拖入 3 列并绑定→发布→运行时读到已发布版本 |
| `tests/e2e/form-designer-perf.e2e.mjs` | 200 字段表单拖入控件响应 ≤300ms |
| `DocExamplesTest`（后端） | 本文与 form-schema.md 中所有 `json lsview` 示例可直接通过校验器 |

E2E 运行：`pnpm test:e2e`（`LSMOM_E2E_BASE` 指定部署地址，默认 `http://127.0.0.1:18081`；只用于测试环境）。
