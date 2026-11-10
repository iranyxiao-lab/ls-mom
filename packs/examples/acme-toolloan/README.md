# 工器具借还（只用配置交付的客户台账）

这个 Pack 只有 YAML / JSON，没有任何 Java 或前端代码。平台的通用台账页、表单渲染、审批流和 Operation Gateway 按这些配置工作。

| 目录 | 内容 |
|---|---|
| `concepts/loan-order.yaml` | 主表"工器具借还单"和子表"借出明细"；借用人引用 `ls.core.Party`，用于设备引用 `ls.asset.Asset`，附件为 `FILE` |
| `operations/loan-order-lifecycle.yaml` | 状态机：草稿 → 审核中 →（退回 ⇄ 再提交）→ 已归档 / 已驳回 |
| `rules/outsource-unit-required.yaml` | 提交时校验：用途为外协必须填外协单位 |
| `views/loan-form.json` | 录入表单（主子表、引用、附件、条件必填规则、各审批节点的字段策略） |
| `views/loan-list.json` | 列表列、筛选字段、行动作 |
| `flows/loan-approval.json` | 审批流：部门审核（车间主任，必填审核意见）→ 确认归档（设备员）；退回发起人后可改预计归还、外协单位、备注再提交 |

## 上线步骤

1. **权限**（系统管理 → 菜单 / 角色）：建按钮权限，授予对应角色。
   - `acme:toolloan:submit`：提交、再提交、撤回，授予发起人（班组长）。
   - `acme:toolloan:flow`：审批节点，授予车间主任与设备员。审批流发布时会检查节点办理人是否具备该权限。
   - `crud:acme.toolloan.LoanOrder:list|query|add|edit|export|import`：台账读写导入导出。
2. **发布**：用 `scripts/pack-build.py` 打包，在协同应用中心上传，生成 ChangeSet，再按"升级演练 → 批准 → 发布"走完门禁。
3. **入口**：通用台账页 `/lsmom/ledger/acme.toolloan.LoanOrder`。待办在工作台办理。

## 改布局

在表单设计器里派生新版本，修改后发布即生成 v2。已按 v1 保存的单据保持冻结，详情按 v1 只读显示，并提示最新版本。编辑旧草稿时，按表单的 `migration.strategy` 决定：提示切换、保持旧版或直接升级。

端到端验证：`plus-ui/tests/e2e/toolloan-config.e2e.mjs`。
