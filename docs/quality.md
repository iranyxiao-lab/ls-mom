# 质量（标准 Pack `ls.quality`）

质量记录、不符合项、受控质量流程（登记 → 车间审核 → 整改工作 → 验证 → 关闭）、整改证据要求、质量终判仅限真人。依赖设备 Pack（不符合项可引用设备）。

## 组成

| 部分 | 位置 |
|---|---|
| Pack 定义（Concept、状态机、规则、视图、黄金场景） | `ls-mom-modules/ls-mom-biz/pack-quality/src/main/resources/lsmom-packs/ls.quality/` |
| 迁移效果与守卫 | `pack-quality` 的 `com.lsmom.biz.quality` |
| 菜单与权限点（编号 3200–3299） | `pack-quality/src/main/resources/db/migration/core/V150__quality_menus.sql` |
| 演示角色授权 | `pack-quality/src/main/resources/db/seed/demo/V5__quality_role_menus.sql` |
| 前端页面 | `plus-ui/src/views/lsmom/quality/`（质量记录、不符合项） |

## 质量记录（DOM-04）

`QualityRecord` 可独立存在，也可通过子表 `QualityRecordLink` 关联工作、设备、产出、物料记录或外部对象中的任意多个，关联都不是必填。`submit`（登记）时在每个关联对象上写一条 `ls.quality.QualityRecord.linked` 事件，工作时间轴（`/api/timeline/ls.core.Work/{id}`）和设备时间轴（`/api/asset/assets/{id}/timeline`，归为"关联"）都能看到。

## 不符合项

| 状态 | 操作 | 说明 |
|---|---|---|
| 草稿 | `submit` 登记提交 | 创建 Issue 外壳（类型 `NONCONFORMITY`，关联设备、工作、来源质量记录）并提交为待处理 |
| 待车间审核 | `audit` 审核通过并派整改 / `reject` 驳回 | 本节点只接受审核意见、立即措施和派工参数；输入里出现其他记录字段即拒绝（`FIELD_READONLY`）。通用编辑接口只允许改草稿（`GENERIC_EDIT_FORBIDDEN`） |
| 整改中 | `submitVerify` 提交验证 | 守卫：整改工作全部完成；至少一份整改证据（整改工作或其填报、不符合项上用途为 EVIDENCE 的附件，或整改工作 / 不符合项上的观察记录） |
| 待验证 | `close` 关闭 / `verifyFail` 不通过 | 终判，只限真人；验证人不能是唯一的整改执行人（约束 Profile 要求分离时） |

- **条件必填**：严重程度为高时相关设备与立即措施必填。表单 `ls.quality.ncForm` 的规则在保存时由前后端同一组 CEL 表达式校验；Pack 规则 `ls.quality.ncHighAsset`、`ls.quality.ncHighAction` 在 `submit` 时再校验一次。
- **终判仅限真人**：Pack 规则 `ls.quality.humanFinalJudgement`（`$context.actor.type == 'HUMAN'`）挂在 `close`、`verifyFail` 上，两者的 Agent 暴露等级为 `DENY`。Agent 或服务身份调用被拒绝（`GUARD_FAILED`）并写入 `op_audit`。
- **整改负责人可见**：`PartyAccess(NonConformance, rectifyOwner)` 让班组里的整改负责人看到并办理派给自己的车间级不符合项。`PartyAccess` 是通用机制，任何 Pack 都可以为指向主体的引用字段声明。

只读接口：`GET /api/quality/objects?type=WORK|ASSET&keyword=`（可关联对象候选）、`GET /api/quality/nc/{id}/rectifications`（整改工作与证据数）。

## 验证

- 后端：`QualityPackTest`（DOM-04 与对象时间轴、高严重度条件必填、受控流程与审核节点只读、整改证据、Agent 终判被拒并审计、驳回与验证不通过）。
- 前端：`tests/unit/quality.spec.ts`；端到端：`tests/e2e/quality-ops.e2e.mjs`（质检员、车间主任、班长三个演示账号走完整闭环）。
