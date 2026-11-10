# 编写 Pack

客户需求和标准扩展都以 **Pack** 交付。Pack 只含定义文件（台账对象、状态机、规则、视图、指标、审批流、迁移、种子和测试），不含可执行代码，所以客户定制不修改核心代码。

一个 Pack 从提交到上线经过 **ChangeSet 发布链路**：校验与依赖分析 → 沙箱安装 → 回归、越权与升级测试 → 人工批准 → 发布。

完整示例见 [`packs/examples/acme-visitor`](../packs/examples/acme-visitor)，下文按它从零编写一个访客登记 Pack。

## 1. 目录结构

```
acme-visitor/
  pack.yaml                    清单（必需）
  concepts/*.yaml              台账对象
  operations/*.yaml            状态机（每个迁移生成一个 Operation）
  rules/*.yaml                 约束规则
  views/*.json                 表单、列表、详情、看板等视图
  metrics/*.yaml               指标口径
  flows/*.json                 审批流
  migrations/V<n>__<说明>.sql  补充迁移
  seed/*.yaml                  种子数据
  tests/scenarios/*.yaml       黄金场景（回归测试）
  README.md                    说明（可选，不安装）
```

规则：

- 只能使用上面这些目录，其他位置的文件会让校验失败。
- 所有定义的编码都以 Pack 编码开头。例如 Pack `acme.visitor` 中的对象为 `acme.visitor.Visit`，Operation 为 `acme.visitor.Visit.checkIn`。
- 同一编码不能在两个文件中重复。

## 2. pack.yaml

```yaml
code: acme.visitor            # 小写、点分，至少两段；也是命名空间
version: 1.0.0                # 主.次.修订；内容有任何变化都要提高版本号
name: 访客登记
description: 门卫登记来访人员，到访签到、离开签退
standard: false               # 标准 Pack 为 true（随平台发布），客户 Pack 省略或 false
owner: { name: 行政部王经理, party: EMP-0012 }   # 业务负责人（必填）；party 为人员编码，复审通知发给此人
requester: { name: 安保队长 }                   # 需求提出人（必填）
reviewDue: 2027-06-30         # 下次复审日期（必填）
dependsOn:                    # 依赖的 Pack 及版本范围（可省略）
  - { code: ls.asset, version: ">=1.0.0 <2.0.0" }
contents:                     # 声明包含的定义，安装器核对与文件一致
  concepts: [acme.visitor.Visit]
  operations: [acme.visitor.Visit.checkIn, acme.visitor.Visit.checkOut]
  views: [acme.visitor.visitForm, acme.visitor.visitList]
  metrics: [acme.visitor.dailyVisits]
entries:                      # 协同应用中心中的入口
  - { name: 访客台账, path: /lsmom/ledger/acme.visitor.Visit }
```

清单字段的完整定义见 `ls-mom-pack/src/main/resources/ls-schema/pack.schema.json`。

- **版本范围**写法：`>=1.0.0 <2.0.0`、`^1.2.0`（同主版本）、`~1.2.0`（同次版本）、`1.x`、`1.2.3`（精确版本）。
- **缺少负责人或提出人时**，打包和提交都会被拒，错误码 `PACK_OWNER_REQUIRED`。
- **依赖要求**：依赖必须已安装并启用，版本满足范围；循环依赖会被拒，错误信息给出完整路径，如 `a → b → a`。
- **入口与导航菜单**：`entries` 中的每个入口在 Pack 启用后自动出现在导航菜单的固定分组里，位置是「分组 → Pack 名称 → 入口名称」，最多三级。
  - 分组用 `group` 指定：`work`（工作）、`ledger`（台账）、`analysis`（分析）、`config`（配置）。
  - 省略 `group` 时，台账路径（`/lsmom/ledger/…`）归入台账，其余归入工作。
  - 台账入口只对有该台账查看权限（`crud:<Concept>:list`）的用户显示。
  - 例：`- { name: 访客统计, path: /lsmom/dashboard/acme.visitor.daily, group: analysis }`。

## 3. 台账对象 concepts/

```yaml
code: acme.visitor.Visit
displayName: 访客登记
fields:
  - { code: visitorName, displayName: 访客姓名, dataType: STRING, required: true, props: { queryable: true, maxLength: 50 } }
  - { code: hostName, displayName: 被访人, dataType: STRING, required: true }
  - { code: visitDate, displayName: 来访日期, dataType: DATE, required: true, props: { sortable: true } }
  - { code: purpose, displayName: 事由, dataType: TEXT }
children:                     # 子表（可选），随主表保存
  - code: acme.visitor.Companion
    displayName: 随行人员
    fields:
      - { code: name, displayName: 姓名, dataType: STRING, required: true }
```

### 字段

- **dataType** 取 `STRING TEXT INTEGER DECIMAL BOOLEAN DATE DATETIME ENUM REF JSON FILE`：
  - `ENUM` 需要 `options`；
  - `REF` 需要 `refConcept`。
- **props** 常用属性：
  - 查询与列表：`queryable`（可筛选）、`sortable`（可排序）、`indexed`、`unique`；
  - 取值限制：`maxLength`、`precision`、`scale`、`dictType`；
  - 读写权限：`readPermission`、`writePermission`。

### 表和系统字段

平台按对象编码生成物理表 `c_<命名空间>_<对象>`，例如 `c_acme_visitor_visit`。表中自动带以下系统字段，不要在 Pack 中声明：

- `id`、`org_id`、`status`、`version`
- 创建人、创建时间

### 给依赖 Pack 的对象追加字段

```yaml
extends: ls.asset.Equipment
fields:
  - { code: certNo, displayName: 证书编号, dataType: STRING }
```

追加的字段归本 Pack 所有。标准 Pack 升级时，这些字段和数据保留，被追加的对象必须属于 `dependsOn` 中的 Pack。

### 破坏性变更

字段不能直接删除或改类型，这类变更是破坏性的。处理方式：

1. 不再使用的字段，先标记 `deprecated: true`。
2. 改类型须走独立审批，并在升级前备份、提供前向修复迁移。
3. 提交 ChangeSet 时，破坏性变更会单独列出。

## 4. 状态机与 Operation operations/

```yaml
code: acme.visitor.Visit.lifecycle
concept: acme.visitor.Visit
displayName: 访客状态
states: [DRAFT, CHECKED_IN, CHECKED_OUT]
initial: DRAFT
transitions:
  - operation: acme.visitor.Visit.checkIn
    name: 签到
    from: [DRAFT]
    to: CHECKED_IN
    permission: "acme:visitor:checkin"   # 必填：越权测试会用无权限身份调用，必须被拒
    risk: C1                              # C0 只读 / C1 一般写入 / C2 高风险
    guards: []                            # 守卫（如 EVIDENCE_REQUIRED）
    inputSchema: { type: object }
```

每个迁移注册为一个 Operation。界面按钮、审批流回调和 Agent 工具都只能通过 Operation 改变状态。

## 5. 视图、规则、指标、审批流

| 目录 | 格式 | 参考 |
|---|---|---|
| `views/*.json` | LS 视图 Schema，`formCode` 为编码，`version` 从 1 开始 | [form-schema.md](form-schema.md) |
| `rules/*.yaml` | `{code, concept, level: L0~L4, kind: CEL, name, message, definition, overrides}` | [rules.md](rules.md) |
| `metrics/*.yaml` | 指标口径 | [metrics.md](metrics.md) |
| `flows/*.json` | 审批流定义（`startOn`、`callbacks`、节点） | [flow-integration.md](flow-integration.md) |

视图规则：

- 视图发布后冻结。修改视图要在文件中提高 `version`，否则安装时报 `VIEW_VERSION_CONFLICT`。
- 内容未变的视图，升级时不会重复发布。

## 6. 补充迁移 migrations/

适用场景：索引、视图、数据修复等平台不会自动生成的 DDL。

- **执行时机**：在台账表生成之后执行，可以引用 Pack 自己的表。
- **执行历史**：每个 Pack 用独立的 Flyway 历史表 `flyway_history_<pack 编码，点换下划线>`，不同 Pack 的版本号互不冲突。
- **重复安装**：已执行的脚本不会再次执行；已执行脚本的内容不能修改，否则校验和不符会让安装失败。
- **执行失败**：失败时报 `PACK_MIGRATION_FAILED`，并指出失败的文件，已安装的旧版本保持可用。

## 7. 种子数据 seed/

```yaml
concept: acme.visitor.Visit
key: visitorName              # 判重字段：已存在的记录不重复插入
rows:
  - { visitorName: 示例访客, hostName: 张工, visitDate: "2026-01-01" }
```

未指定 `orgId` 时，记录归属根组织。

## 8. 黄金场景 tests/scenarios/

```yaml
name: 访客登记、签到、签退
steps:
  - create: { concept: acme.visitor.Visit, as: v, values: { visitorName: 测试访客, hostName: 张工, visitDate: "2026-11-10" } }
  - expectError: { code: INVALID_TRANSITION, op: { code: acme.visitor.Visit.checkOut, target: v } }
  - op: { code: acme.visitor.Visit.checkIn, target: v }
  - expect: { concept: acme.visitor.Visit, target: v, status: CHECKED_IN }
```

| 步骤 | 说明 |
|---|---|
| `create` | 新建记录，`as` 命名供后续步骤引用 |
| `op` | 执行 Operation（自动带上记录的当前版本） |
| `expect` | 校验状态和字段值 |
| `expectError` | 期望某一步以指定错误码失败 |

每个场景在单独事务中执行并回滚，不留数据。

除了场景，测试还会自动做**越权检查**：用无权限身份新增每个台账对象、执行每个 Operation，都必须被拒（`ACCESS_DENIED`）。

## 9. 打包

```bash
python3 scripts/pack-build.py packs/examples/acme-visitor
```

生成 `acme-visitor-1.0.0.zip`，根目录含 `MANIFEST.sha256`，每行 `<sha256>  <路径>`，格式与 `sha256sum` 相同。

- 平台读取 zip 时逐个核对文件，多出、缺少或内容不符都会被拒（`PACK_CHECKSUM_MISMATCH`）。
- 同样内容总是得到同样的 zip。

## 10. 发布

1. **提交 ChangeSet**：打开 **协同应用中心 → 变更发布**，上传 zip。平台立即做校验与依赖分析，不通过的直接驳回，并给出原因。
2. **升级演练**：在临时环境中完成沙箱安装和测试。

   ```bash
   LSMOM_E2E_BASE=https://<测试环境> deploy/scripts/upgrade-drill.sh --changeset <ID> --backup-from <Compose 项目名>
   ```

   脚本依次执行：

   1. 备份目标环境，恢复到临时 Compose 项目 `lsmom-drill`；
   2. 用当前版本启动，执行核心迁移；
   3. 安装制品，运行本 Pack 和所有已启用 Pack 的测试；
   4. 把沙箱、回归、越权、升级四个阶段的结果回写到 ChangeSet；
   5. 删除临时环境。

   任何一步失败，脚本都以非零退出，并输出失败的迁移或测试。也可以用 `--backup <备份目录>` 指定已有的备份。
3. **门禁检查**：`deploy/scripts/release-gate.sh <ID>` 列出未通过的阶段，可接入 CI。
4. **人工批准**：四项测试全部通过后，ChangeSet 进入"待审批"，由有 `lsmom:changeset:review` 权限的人批准或驳回。
5. **发布**：批准后点"发布"安装到当前环境。未批准、任一阶段未通过、或阶段结果不是针对当前制品时，发布都会被拒（`CHANGESET_NOT_APPROVED` / `CHANGESET_GATE_FAILED`）。

正式环境不提供直接安装接口。沙箱和演练环境设置 `LSMOM_PACK_DIRECT_INSTALL=true`，允许 `POST /api/packs/install` 直接安装。

## 11. 运行期管理

在 **协同应用中心** 中可以查看每个 Pack 的以下信息：

- 版本，以及是否有待升级版本
- 依赖和被依赖关系
- 负责人、提出人、复审日期
- 近 90 天使用量、最近使用时间、失败任务
- 配置漂移

### 启停

- **停用**：页面、Operation、Agent 工具都不可用，数据保留，可只读导出。被其他已启用 Pack 依赖时不能停用。
- **启用**：依赖必须已启用。

### 配置漂移

平台把运行库中的定义与安装时记录的基线比较（规范化哈希），以下情况都会被发现：

- 有人直接改表或改库；
- 在设计器里另发了版本。

漂移报告逐项列出差异路径和前后值。**将差异回灌为 ChangeSet** 会生成修订号加一的新制品，其中漂移的文件已替换为当前定义，再走同样的发布链路。

### 复审

每日 07:30 检查一次，也可以在页面上手动触发：

| 情况 | 状态 |
|---|---|
| 到达复审日期 | 待复审 |
| 到期且 90 天无使用 | 建议下线 |

状态变化时，通过消息中心通知业务负责人。复审后，在 pack.yaml 中更新 `reviewDue`，并发布新版本。

## 12. 升级

标准 Pack 升级时：

- 客户 Pack 追加的字段和数据保留；
- 文件未变的定义不重复发布；
- 已安装 Pack 对它的版本要求仍须满足，否则升级被拒（`PACK_DEPENDENT_BROKEN`）。

以下操作被拒：

- 安装比已安装版本更低的版本（`PACK_DOWNGRADE`）；
- 同版本号但内容不同（`PACK_VERSION_CONFLICT`）。

要回退，请恢复备份，或发布更高版本的修复。
