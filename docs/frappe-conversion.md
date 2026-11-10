# Frappe 静态转换

`ls-mom-cli frappe-import` 把 Frappe 应用的资产目录静态转换为 LS-IR（见 [ls-ir.md](ls-ir.md)），再用 `generate` 生成 Pack 与 ChangeSet。

**只读 JSON，不执行任何代码**：Python（`.py`）、Server Script、Client Script、Script Report 一律不运行、不翻译，只记录为需人工或禁止自动转换项。转换结果与普通生成物一样，必须经 ChangeSet 测试、处理全部需人工项并人工批准后才能发布。

## 用法

```bash
ls-mom-cli frappe-import apps/my_app/my_app --namespace acme.todo --app my_app --version 15.2.0 --out todo.ir.json
ls-mom-cli generate todo.ir.json --out build/acme-todo
```

- `--namespace`：目标 Pack 编码；DocType 名称转为 PascalCase 对象编码（`ToDo` → `acme.todo.ToDo`）。
- `--license`：指定 SPDX 许可证；不指定时识别目录下的 `LICENSE` / `license.txt`。
- 命令输出对象数、状态机数、需人工项与其中禁止自动转换项的数量，并立即做 LS-IR 校验。

## 映射规则

| Frappe | LS-IR | 说明 |
|---|---|---|
| DocType | Concept | `source` 记录 DocType 名与文件路径；`istable=1` 的 DocType 只作为子表 |
| Table 字段 | 子表 `children` | 子表 DocType 不在转换包中时需人工 |
| Data、Color、Autocomplete、Phone | STRING | `length` → `maxLength`（默认 140） |
| Small Text、Text、Long Text、Text Editor、Markdown / HTML Editor、Code | TEXT | |
| Int、Duration / Float、Currency、Percent、Rating | INTEGER / DECIMAL | |
| Check / Date / Datetime | BOOLEAN / DATE / DATETIME | |
| Select | ENUM | 选项转为大写编码，原文进 `optionLabels` |
| Attach、Attach Image、Signature / JSON、Geolocation | FILE / JSON | |
| Link → User | REF `ls.core.Party` | 记入 `reuse` |
| Link → 转换包内的 DocType | REF 本包对象 | |
| Link → 其他（Role、DocType、包外 DocType） | STRING ＋ 需人工 | |
| Dynamic Link | STRING ＋ 需人工 | |
| Section / Column / Tab Break、HTML、Button、Heading、Read Only 等 | 不转换 | 布局与只读展示字段 |
| Password | 禁止自动转换 | |
| 其他字段类型 | STRING ＋ 需人工 | |
| `reqd` / `in_list_view`、`in_standard_filter` / `unique` | `required` / `queryable` / `unique` | 字段按 `field_order` 排序 |
| Workflow | STATE_MACHINE | 状态转为大写编码，**第一个状态映射为平台的 `DRAFT`**（新建记录的状态）；工作流状态字段不再作为普通字段 |
| Workflow Transition | 迁移 ＋ OPERATION 权限点 | 同名动作合并 `from`；`allowed` 角色作为权限点的建议角色；`condition`（Python 表达式）需人工改写为 CEL 规则；同一动作去往不同状态需人工拆分 |
| DocPerm | CRUD 权限点与角色 | read → list/query，create → add，write → edit，delete → remove；`permlevel>0`、`if_owner` 需人工映射到字段权限与数据域 |
| Report（Report Builder） | count 指标 | 按 `createdAt` 日粒度、按状态分组 |
| Report（Query Report） | 需人工 | 须人工改写为指标口径 |
| Report（Script Report） | 禁止自动转换 | |
| Fixture（`[{"doctype": …}]` 数组） | 种子 | 只取已映射的非引用字段；ENUM 值转编码 |
| Patch、其他 `.py` | 需人工 | 不执行 |
| Server Script | 禁止自动转换 | |
| Client Script、`.js` | 禁止自动转换 | |
| 许可证未知或 GPL / AGPL / LGPL | 整体禁止自动转换 | 允许：MIT、Apache-2.0、BSD-2/3-Clause、ISC |

场景测试：没有必填引用字段时，自动生成"登记并沿无条件迁移流转"的场景；有必填引用时需人工补充测试数据。

## 需人工项与发布门禁

转换结果的 `provenance.manualRequired` 列出每一项需人工（`MANUAL`）和禁止自动转换（`BLOCKED`）的内容及原因。处理方式：

1. 按需修改 LS-IR 或生成物，并用 `record-edit` 记录人工修改（修改人、位置、原因、行数）；
2. 用 `resolve --path <项路径> --by <人> --resolution <处理结论>` 关闭该项（路径可写到文件，关闭其下全部字段项）；
3. 重新 `generate`（通常同时升版本），提交新的 ChangeSet。

只要还有未关闭项，ChangeSet 发布门禁就不通过（`禁止自动转换项未处理：…` / `需人工项未处理：…`）。

## 来源查询

安装后，`GET /api/packs/{code}/provenance?concept=acme.todo.ToDo` 返回：来源类型与应用版本、许可证、转换器版本、模型（Frappe 转换为 `null`）、该对象及其字段 / 状态机迁移的 Frappe 来源（DocType、字段名、文件路径），以及相关的人工修改历史和需人工项。不带 `concept` 时返回整个 Pack 的来源记录。

## 样例

`ls-mom-cli/src/test/resources/frappe/todo-app/`：frappe/frappe 的 ToDo DocType（MIT，来源见该目录 `ATTRIBUTION.md`）＋自编 Workflow、Report Builder / Script Report、Fixture、Patch、Server Script、Client Script。`FrappeImporterTest` 覆盖：转换、校验、生成、临时库安装、场景测试通过、来源查询、含 Server Script 时 ChangeSet 不能发布、逐项处理后发布成功。
