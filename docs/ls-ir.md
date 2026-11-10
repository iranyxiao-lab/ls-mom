# LS-IR 1.0

LS-IR 是生成与转换的中间表示：需求文本、字段表格、旧表结构（构建期 Copilot）和 Frappe 资产（`frappe-import`）都先变成 LS-IR，经校验后再由 `generate` 生成 Pack、代码骨架、测试与 ChangeSet 制品。LS-IR 本身不能安装，也不能发布。

- Schema：`ls-mom-cli/schema/ls-ir-1.0.json`（JSON Schema 2020-12，随 CLI jar 发布）
- 校验：`ls-mom-cli validate <ir.json|ir.yaml>`，先做 Schema 校验，再做语义校验（见下文）

## 顶层结构

| 键 | 必填 | 说明 |
|---|---|---|
| `irVersion` | 是 | 固定为 `"1.0"` |
| `namespace` | 是 | 命名空间，同时是 Pack 编码（如 `acme.lube`）。所有编码都以它开头，`ls.core.*` 只能引用不能新建 |
| `pack` | 是 | 版本（SemVer）、名称、负责人 `owner`、提出方 `requester`、复审日期 `reviewDue`，可选 `dependsOn`、`entries` |
| `reuse` | 否 | 语义复用检查的结果：引用的已有对象（`concept`、`reason`、`term`、`pack`、`labelField`） |
| `concepts` | 是 | 业务对象：字段、子表 `children` |
| `views` | 是 | 表单视图：字段顺序、控件、动作按钮 |
| `flows` | 是 | 审批流（`startOn`、`nodes`、`callbacks`、`form`） |
| `operations` | 是 | `STATE_MACHINE`（状态、初始状态、迁移）或 `HANDLER`（代码 Operation，生成骨架） |
| `rules` | 是 | CEL 规则，`appliesTo` 指向 Operation |
| `permissions` | 是 | 权限点与建议角色（`OPERATION`、`CRUD`、`PAGE`） |
| `metrics` | 是 | 指标（`count/sum/avg/min/max`，时间字段、粒度、维度） |
| `migrations` | 是 | 附加 SQL 迁移（建索引等），按 `version` 生成 `V<n>__*.sql` |
| `seeds` | 否 | 种子数据（`concept`、判重 `key`、`rows`） |
| `tests` | 是 | 场景测试：`create`、`op`（可带 `as: HUMAN`）、`expect`、`expectError` |
| `provenance` | 是 | 来源追溯（见下文） |

字段类型：`STRING TEXT INTEGER DECIMAL BOOLEAN DATE DATETIME ENUM REF FILE JSON`。`ENUM` 必须有 `options`（大写编码），可带 `optionLabels`；`REF` 用 `refConcept` 指向本 IR 的对象、`ls.core.*` 或 `reuse` 中声明的对象。字段、对象、迁移都可带 `source`（`type`、`name`、`field`、`path`），记录它从哪里来。

## 来源追溯 `provenance`

| 键 | 说明 |
|---|---|
| `sourceType` | `REQUIREMENT`、`FRAPPE`、`LEGACY_SCHEMA`、`MANUAL` |
| `source` | 来源应用 `app`、版本 `version`、路径 `path`、需求编号 `ref`、摘要 `digest` |
| `license` | SPDX 标识；`UNKNOWN` 表示无法确认（禁止自动转换） |
| `converter` | 转换器 / 生成器名称与版本 |
| `model` | 用了 LLM 时的模型名称、版本（`模型@指纹`）、端点、提示摘要；没用时为 `null` |
| `generatedAt` | 生成时间 |
| `manualEdits` | 人工修改历史：时间、修改人、位置、原因、行数（`ls-mom-cli record-edit` 追加） |
| `manualRequired` | 需人工处理项：`MANUAL`（需人工）或 `BLOCKED`（禁止自动转换），状态 `OPEN` / `RESOLVED`（`ls-mom-cli resolve` 关闭） |
| `metrics` | 可选：`requirementAt`、`uatAt`，用于计算需求到 UAT 工时 |

`generate` 把来源记录写进 Pack 的 `provenance.json`，并附上各对象、字段、子表、状态机迁移的来源索引。安装后用 `GET /api/packs/{code}/provenance?concept=<对象编码>` 查询某个对象的来源、许可证、转换器版本和人工修改历史。

**有未关闭的 `manualRequired` 项时，ChangeSet 发布门禁不通过**，即使沙箱、回归、越权、升级测试都已通过并经过批准。

## 语义校验

Schema 之外，`validate` 还检查：

- 编码以 `namespace` 开头；不能新建 `ls.core.*`；
- `REF` 指向的对象存在（本 IR、核心对象或 `reuse`）；引用其他 Pack 的对象时，`pack.dependsOn` 必须声明该 Pack；
- 视图字段、子表键、动作 Operation 存在；状态机的初始状态与迁移状态在 `states` 中；
- 迁移、Handler 使用的权限点已在 `permissions` 中声明；规则 `appliesTo` 指向已有 Operation；
- 审批流的 `startOn` 与回调指向已有 Operation；
- 有 C2 及以上风险 Operation 的对象不能通用新增，场景测试不能直接 `create` 它。

## 示例

下面的示例可以直接通过校验（`ls-mom-cli/src/test/java/com/lsmom/cli/DocExamplesTest.java` 会校验本文档中的每个 JSON 代码块）：

```json
{
  "irVersion": "1.0",
  "namespace": "acme.lube",
  "pack": {
    "version": "1.0.0",
    "name": "设备润滑",
    "owner": { "name": "设备部" },
    "requester": { "name": "一车间" },
    "reviewDue": "2027-12-31"
  },
  "reuse": [
    { "concept": "ls.core.Party", "reason": "润滑人复用平台人员", "term": "润滑人", "labelField": "name" }
  ],
  "concepts": [
    {
      "code": "acme.lube.LubeRecord",
      "displayName": "润滑记录",
      "fields": [
        { "code": "pointNo", "displayName": "润滑点", "dataType": "STRING", "required": true, "queryable": true, "maxLength": 40 },
        { "code": "oil", "displayName": "油品", "dataType": "ENUM", "required": true, "options": ["L_HM46", "L_CKD220"],
          "optionLabels": { "L_HM46": "L-HM46 抗磨液压油", "L_CKD220": "L-CKD220 齿轮油" } },
        { "code": "volume", "displayName": "加注量（L）", "dataType": "DECIMAL" },
        { "code": "lubricator", "displayName": "润滑人", "dataType": "REF", "refConcept": "ls.core.Party" }
      ]
    }
  ],
  "views": [
    {
      "code": "acme.lube.lubeRecordForm",
      "concept": "acme.lube.LubeRecord",
      "title": "润滑记录",
      "fields": [ { "key": "pointNo" }, { "key": "oil" }, { "key": "volume" }, { "key": "lubricator" } ],
      "actions": [ { "key": "confirm", "label": "确认", "operation": "acme.lube.LubeRecord.confirm" } ]
    }
  ],
  "flows": [],
  "operations": [
    {
      "kind": "STATE_MACHINE",
      "code": "acme.lube.LubeRecord.lifecycle",
      "concept": "acme.lube.LubeRecord",
      "states": ["DRAFT", "CONFIRMED"],
      "initial": "DRAFT",
      "transitions": [
        { "operation": "acme.lube.LubeRecord.confirm", "name": "确认", "from": ["DRAFT"], "to": "CONFIRMED",
          "permission": "acme:lube:luberecord:confirm", "risk": "C1" }
      ]
    }
  ],
  "rules": [],
  "permissions": [
    { "code": "acme:lube:luberecord:confirm", "name": "确认润滑记录", "kind": "OPERATION", "roles": ["设备员"] }
  ],
  "metrics": [
    { "code": "acme.lube.lubeCount", "name": "润滑次数", "concept": "acme.lube.LubeRecord", "measure": { "fn": "count" },
      "timeField": "createdAt", "grains": ["DAY", "MONTH"], "dimensions": ["oil"] }
  ],
  "migrations": [],
  "tests": [
    {
      "name": "登记并确认润滑记录",
      "steps": [
        { "create": { "concept": "acme.lube.LubeRecord", "as": "r", "values": { "pointNo": "P101-DE", "oil": "L_HM46" } } },
        { "op": { "code": "acme.lube.LubeRecord.confirm", "target": "r" } },
        { "expect": { "concept": "acme.lube.LubeRecord", "target": "r", "status": "CONFIRMED" } }
      ]
    }
  ],
  "provenance": {
    "sourceType": "REQUIREMENT",
    "source": { "app": "需求评审会", "ref": "REQ-LUBE-01" },
    "license": "LicenseRef-Customer-Owned",
    "converter": { "name": "ls-mom-cli copilot", "version": "1.0.0" },
    "model": null,
    "generatedAt": "2026-11-10T08:00:00Z",
    "manualEdits": [],
    "manualRequired": []
  }
}
```

## 命令

```bash
ls-mom-cli validate acme-lube.ir.json
ls-mom-cli generate acme-lube.ir.json --out build/acme-lube
ls-mom-cli copilot 需求.txt --namespace acme.lube --out acme-lube.ir.json [--catalog <Pack 目录>] [--replay <录制目录>]
ls-mom-cli frappe-import <Frappe 资产目录> --namespace acme.todo --out todo.ir.json [--license MIT] [--app 名称] [--version 版本]
ls-mom-cli record-edit todo.ir.json --by 实施顾问 --path concepts/to-do.yaml --reason "原因" [--lines 2]
ls-mom-cli resolve todo.ir.json --path server_script/auto_close.json --by 实施顾问 --resolution "处理结论"
ls-mom-cli record-run build/acme-lube --stage compile|install|test --result PASSED|FAILED
ls-mom-cli quality build/acme-lube build/acme-todo --out quality.json
```

`generate` 的输出目录：

```
pack/                     Pack 目录（可直接安装到临时库或打包）
java/src/main|test/java   代码 Operation 骨架（抛 NOT_IMPLEMENTED，须人工实现）与 JUnit 测试
changeset/<pack>-<版本>.zip ChangeSet 制品（含 sha256 清单）
report.json               本次生成的指标
```

## 不直达生产

CLI 只产出文件，没有发布能力，也不连接任何环境。产物只能以 ChangeSet 提交，走沙箱安装、回归、越权、升级测试和人工批准；**发布只接受真人**——服务账号、Agent、系统身份调用发布都返回 `CHANGESET_RELEASE_HUMAN_ONLY`。

## 生成质量指标

每次 `generate` 写 `report.json`：校验结果、新建与复用对象数、复用比例、人工修改与原因、需人工项（未关闭 / 禁止自动转换）、需求到 UAT 工时。编译、临时库安装、测试的结果用 `record-run` 追加，第一次记录即"一次通过"与否。`quality` 汇总多次生成：

| 指标 | 含义 |
|---|---|
| `validationPassRate` | 校验通过率 |
| `compileFirstPassRate` / `installFirstPassRate` / `compileAndInstallFirstPassRate` | 一次编译、安装成功率 |
| `testFirstPassRate` | 测试一次通过率 |
| `manualEdits` | 人工修改次数、行数、按原因统计 |
| `reuseRatio` | 复用对象 /（新建＋复用） |
| `manualRequired` | 未关闭的需人工项、其中禁止自动转换项 |
| `requirementToUatHoursAvg` | 平均需求到 UAT 工时 |
