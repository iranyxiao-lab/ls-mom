# 表单与视图 Schema

产品只存储 **LS 视图 Schema**（`meta_view.schema`），不存储任何设计器私有格式。六种视图共用一套版本、发布与兼容性规则：

| viewType | JSON Schema | 用途 |
| --- | --- | --- |
| `FORM` | `ls-schema/form.schema.json` | 录入/编辑表单（含子表、规则、动作、节点策略） |
| `LIST` | `list.schema.json` | 台账列表：列、筛选、默认排序、行动作、导出 |
| `DETAIL` | `detail.schema.json` | 详情：分组字段、子表、时间轴、附件 |
| `PRINT` | `print.schema.json` | 打印模板：纸张、表头、分节、签字栏 |
| `MOBILE` | `mobile.schema.json` | 移动端：字段顺序、离线草稿、动作 |
| `DASHBOARD` | `dashboard.schema.json` | 看板：指标卡、图表（只绑定指标或命名查询，禁止 SQL） |

JSON Schema 采用 2020-12，位于 `ls-mom-form/src/main/resources/ls-schema/`；TS 类型见 `plus-ui/src/types/lsView.ts`。

## 校验分两层

1. **结构校验**（JSON Schema）：未知属性、未知控件、类型错误都会被拒，问题定位到 JSON Pointer（如 `/fields/2/widget`）。
2. **语义校验**（`ViewSchemaValidator`）：

| 编码 | 规则 |
| --- | --- |
| `FIELD_NOT_BOUND` / `FIELD_STATE_FORBIDDEN` / `FIELD_DUPLICATE` | 字段必须是 Concept 的已发布字段且只定义一次；状态字段不能放进录入表单 |
| `LAYOUT_REF_INVALID` / `LAYOUT_DUPLICATE` / `FIELD_NOT_PLACED` | 布局只能引用已定义字段，每个字段恰好放一次 |
| `PATTERN_INVALID` / `RANGE_INVALID` / `DICT_MISSING` | 正则可编译、`min ≤ max`、字典类型存在 |
| `SUBTABLE_INVALID` | 子表必须是本 Concept 的子 Concept，键名为子 Concept 名首字母小写（`LoanLine` → `loanLine`） |
| `REF_INVALID` | 引用目标 Concept 与过滤字段必须存在；过滤值只允许常量、`$context.orgId/userId/deptId`、`$model.<字段>` |
| `SCHEMA_*` / `COMPUTED_INVALID` | 规则与计算字段只能是受限 CEL（只读 `$model`、`$context`，不能调用外部函数） |
| `COMPONENT_NOT_REGISTERED` / `COMPONENT_INCOMPATIBLE` | `registered` 控件必须引用已登记且兼容当前表单版本的组件 |
| `OPERATION_NOT_PUBLISHED` / `OPERATION_CONCEPT_MISMATCH` / `ACTION_DUPLICATE` | 动作只能绑定本 Concept 的已发布 Operation，键不重复 |
| `POLICY_FIELD_INVALID` | 节点策略只能引用表单字段 |
| `FIELD_NOT_QUERYABLE` / `FIELD_NOT_SORTABLE` | 列表筛选/排序只能用 Concept 声明可筛选/可排序的字段 |
| `DASHBOARD_SQL_FORBIDDEN` / `DASHBOARD_BINDING_MISSING` | 看板任何位置出现 SQL 都被拒，组件必须绑定 `metric` 或 `query` |
| `FORM_SCRIPT_FORBIDDEN` | 任何位置出现脚本（`<script>`、`javascript:`、`on*=`、`function(`、`=>`、`eval`、`${`……）或脚本类键（`onClick`、`handler`……）都被拒 |

接口：`POST /api/form/validate` 只校验不保存；保存草稿与发布都会重新校验。

## 录入表单（FORM）

下面的示例与测试样例一致，可直接通过校验（`DocExamplesTest` 会读取本文所有 `json lsview` 代码块，把 `acme.tool` 替换成测试台账的命名空间后校验）。

```json lsview
{
  "viewType": "FORM",
  "formCode": "acme.tool.loanForm",
  "version": 1,
  "conceptCode": "acme.tool.LoanOrder",
  "title": "工器具借还单",
  "labelWidth": 110,
  "layout": [
    { "type": "row", "children": [
      { "type": "col", "span": 12, "children": [{ "type": "field", "key": "loanNo" }] },
      { "type": "col", "span": 12, "children": [{ "type": "field", "key": "borrower" }] }
    ] },
    { "type": "group", "title": "借用信息", "children": [
      { "type": "field", "key": "loanDate" },
      { "type": "field", "key": "purpose" },
      { "type": "field", "key": "toolRef" },
      { "type": "field", "key": "remark" },
      { "type": "field", "key": "qtyNote" },
      { "type": "field", "key": "auditNote" }
    ] },
    { "type": "subTable", "key": "loanLine", "title": "借出明细" }
  ],
  "fields": [
    { "key": "loanNo", "label": "借用单号", "widget": "input", "required": true, "maxLength": 32,
      "pattern": "^L-[0-9A-Z-]+$", "patternMessage": "单号须以 L- 开头" },
    { "key": "borrower", "label": "借用人", "widget": "input", "required": true },
    { "key": "loanDate", "label": "借出日期", "widget": "date", "required": true },
    { "key": "purpose", "label": "用途", "widget": "select", "options": [
      { "value": "MAINTENANCE", "label": "检修" }, { "value": "INSPECTION", "label": "巡检" }, { "value": "OTHER", "label": "其他" }
    ] },
    { "key": "toolRef", "label": "主要工器具", "widget": "assetRef",
      "ref": { "concept": "acme.tool.Tool", "labelField": "name",
               "filter": [{ "field": "orgId", "op": "eq", "value": "$context.orgId" }] } },
    { "key": "remark", "label": "备注", "widget": "textarea", "maxLength": 200 },
    { "key": "qtyNote", "label": "数量说明", "widget": "computed",
      "computed": { "expression": "string(size($model.loanLine)) + ' 项'" } },
    { "key": "auditNote", "label": "审核意见", "widget": "textarea", "readonly": true }
  ],
  "subTables": [
    { "key": "loanLine", "label": "借出明细", "conceptCode": "acme.tool.LoanLine", "minRows": 1, "maxRows": 20, "fields": [
      { "key": "tool", "label": "工器具", "widget": "input", "required": true },
      { "key": "qty", "label": "数量", "widget": "number", "required": true, "min": 1, "max": 99 },
      { "key": "returned", "label": "已归还", "widget": "switch" }
    ] }
  ],
  "rules": [
    { "code": "remarkWhenOther", "level": "L1", "when": "$model.purpose == 'OTHER'",
      "assert": "has($model.remark) && size($model.remark) > 0", "field": "remark", "message": "用途为其他时必须填写备注" }
  ],
  "actions": [
    { "key": "submit", "label": "提交", "operation": "acme.tool.LoanOrder.submit", "style": "primary", "confirm": "确认提交？" }
  ],
  "nodePolicies": {
    "deptAudit": { "readonly": ["*"], "editable": ["auditNote"], "required": ["auditNote"] }
  },
  "migration": { "strategy": "PROMPT", "note": "新版本增加了字段，旧草稿可继续按旧版填写" }
}
```

### 控件

| 分组 | widget |
| --- | --- |
| 基础 | `input` `textarea` `number` `money` `date` `datetime` `switch` `dict`（`dictType`） `rate` |
| 选择 | `select` `radio` `checkbox` `cascader` `remoteSelect`（均用 `options`，`multiple` 多选） |
| 业务引用 | `userRef` `deptRef` `teamRef` `assetRef` `workRef` `materialRef` `pointRef` `conceptRef`（`ref.concept` 必填） |
| 证据 | `attachment` `image` `file`（`accept`、`maxCount`） `sourceRef` `confirm` |
| 展示/高级 | `readonlyText` `computed`（`computed.expression`，始终只读，服务端重算） `expression` `registered`（`component.code/version`） |

布局节点：`field` `subTable` `group` `row`/`col` `tabs`/`tab` `collapse`/`panel` `divider` `text` `description` `metricCard` `relatedSummary` `relatedList`。

### 规则

`level`：`L1` 阻断保存、`L2` 阻断提交（由 Operation 守卫执行）、`L3` 只提示。`when` 求值失败（例如可选字段缺失）视为规则不适用；`assert` 为假时把 `message` 挂到 `field` 上。前端（`@bufbuild/cel`）与后端（cel-java）用同一表达式，结果一致。

### 字段策略

节点策略 `{ readonly, hidden, editable, required }`，值为字段键数组，`*` 表示全部。合并优先级：

1. 显式 `editable` 高于 `readonly`/`hidden`（含 `*`）；
2. `readonly`/`hidden`（显式或 `*`）高于表单默认；
3. `computed` 字段始终只读；`required` 字段一定可见；
4. 子表列受子表本身的策略约束，除非该列被显式 `editable`。

Java（`FieldPolicy`）与 TS（`resolveFieldPolicy`）两端实现，共享用例集 `packs/_shared/field-policy/*.json` 同时驱动两边的测试。Operation 网关执行时由 `FormFieldPolicyCheck` 拒绝写入只读字段（`FIELD_READONLY`）或缺少必填字段（`VALIDATION_FAILED`）。

## 服务端记录校验

通用 CRUD 写入时，`FormRecordValidator` 按**冻结版本**重新校验：

- 选用表单：请求中的 `_form.code`，否则 Concept 的默认已发布表单；
- 选用版本：`_form.version` → 记录已冻结的 `schemaVersion` → 当前版本，**不允许降级**；新建时把版本写入记录的 `schemaVersion`；
- 检查必填、长度、范围、正则、选项、字典（`sys_dict_data`）、引用过滤（越过过滤或缺少上下文变量一律拒绝）、子表行数，字段错误清除后再执行 L1/L2 规则；
- 计算字段由服务端重算并覆盖提交值；写入只读字段报「为只读，不能修改」。

错误以 400 `VALIDATION_FAILED` 返回，`details.fields` 为 字段 → 原因，前端 `setServerErrors` 直接挂到对应控件。

## 版本、发布与兼容性

- 每个 `(formCode, version)` 发布后**不可变**；只能派生新草稿（`POST /api/form/views/{code}/derive`），保存不高于已发布版本号的草稿返回 409 `FORM_VERSION_IMMUTABLE`。
- 发布生成 `VIEW_PUBLISH` 变更集：无删除字段时直接生效；删除了字段则进入 `REVIEWING`，须由**他人**评审通过后 `apply`。生效时旧版本置为 `RETIRED`，仍可读。
- 兼容性检查（`GET /api/form/views/{code}/{v}/check`）：
  - `OPERATION_INPUT_INCOMPATIBLE`：动作所需输入字段不在表单中；
  - `POLICY_FIELD_REMOVED`：流程节点策略引用了被删字段；
  - `RULE_FIELD_REMOVED`：已发布规则引用了被删字段；
  - `PRINT_FIELD_REMOVED`：已发布打印模板引用了被删字段。
- 历史记录按其冻结版本只读渲染（`SchemaDetail` 显示「按 vN 填写」提示）。
- 编辑旧版本记录：`GET /api/form/views/{code}/edit-version?recordVersion=n` 按 `migration.strategy` 返回 `KEEP`（继续旧版）、`UPGRADE`（升级到当前版本）或 `PROMPT`（让用户选择）。

## 其他视图示例

```json lsview
{ "viewType": "LIST", "formCode": "acme.tool.loanList", "version": 1, "conceptCode": "acme.tool.LoanOrder",
  "columns": ["loanNo", { "key": "borrower", "width": 120 }, "loanDate", "status"], "filters": ["loanNo", "purpose"],
  "defaultSort": [{ "field": "loanDate", "dir": "desc" }],
  "rowActions": [{ "key": "submit", "label": "提交", "operation": "acme.tool.LoanOrder.submit" }], "export": { "enabled": true } }
```

```json lsview
{ "viewType": "DETAIL", "formCode": "acme.tool.loanDetail", "version": 1, "conceptCode": "acme.tool.LoanOrder",
  "groups": [{ "title": "基本信息", "columns": 2, "fields": ["loanNo", "borrower", "loanDate", "status"] }],
  "subTables": ["acme.tool.LoanLine"], "timeline": true, "attachments": true }
```

```json lsview
{ "viewType": "PRINT", "formCode": "acme.tool.loanPrint", "version": 1, "conceptCode": "acme.tool.LoanOrder",
  "templateVersion": "1.0.0", "paper": "A4", "header": { "title": "工器具借还单" },
  "sections": [{ "fields": ["loanNo", "borrower"], "columns": 2 }, { "fields": [], "subTable": "acme.tool.LoanLine" }],
  "signatures": [{ "label": "借用人" }, { "label": "审核人", "role": "dept_leader" }] }
```

```json lsview
{ "viewType": "MOBILE", "formCode": "acme.tool.loanMobile", "version": 1, "conceptCode": "acme.tool.LoanOrder",
  "fields": ["loanNo", "borrower", "loanDate"], "offlineDraft": true,
  "actions": [{ "key": "submit", "label": "提交", "operation": "acme.tool.LoanOrder.submit" }] }
```

```json lsview
{ "viewType": "DASHBOARD", "formCode": "acme.tool.board", "version": 1,
  "widgets": [{ "type": "metricCard", "title": "借出", "metric": "acme.tool.loanCount" }] }
```

## 注册组件

`registered` 控件只能使用组件注册表（`form_component`）中的组件：`POST /api/form/components` 登记编码、版本、显示名、输入/输出 Schema、所需权限、兼容的表单 Schema 版本区间（`compatFrom`～`compatTo`）和测试用例引用（`testRef`，必填）。登记内容同样经过脚本扫描，任何脚本都会被拒（`COMPONENT_REJECTED`）。

## 接口一览

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/form/views`、`/views/{code}?version=`、`/views/{code}/history` | 登录 | 视图列表、指定版本、版本历史 |
| `GET /api/form/concept/{concept}/default?type=FORM` | 登录 | Concept 的默认已发布视图 |
| `GET /api/form/views/{code}/{v}/policy?node=` | 登录 | 节点字段策略 |
| `POST /api/form/ref/search` | 登录（受控台账需 `crud:<concept>:list`） | 引用候选，按过滤与数据域，最多 20 条 |
| `POST /api/form/drafts`、`/validate`、`/views/{code}/derive`；`DELETE /views/{code}/{v}` | `lsmom:form:design` | 草稿 |
| `POST /api/form/views/{code}/{v}/publish`、`/changesets/{id}/apply` | `lsmom:form:publish` | 发布 |
| `POST /api/form/changesets/{id}/review` | `lsmom:form:review` | 评审（不能评审自己的变更） |
