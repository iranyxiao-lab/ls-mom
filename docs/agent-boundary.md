# Agent 能力边界

运行期 Agent（MCP 客户端、交接班助手等）与人走同一个 Operation 网关。本文说明 Agent 能做什么、不能做什么，以及这些边界由哪些机制保证。规格见 `openspec/changes/bootstrap-ls-mom-mvp/specs/ai/agent-gateway/spec.md`。

## 身份与有效权限

- Agent 是独立的服务主体（`agent_identity`，对应一个 `AGENT` 类型的 Party），以 API Key 调用：`lsk_<12 位前缀>_<40 位密钥>`，库中只存 SHA-256 摘要。Key 可轮换（旧 Key 宽限期内可用）、吊销（立即失效），每个 Agent 有每分钟调用上限；签发、轮换、吊销、限流与吊销后使用都记入 `agent_key_event`。
- 每次调用都代表一个真人（OBO）：请求同时带真人登录令牌与 `X-Agent-Key`。
- **有效权限 = 真人权限 ∩ Agent 授权**；数据域取真人的数据域。任何一方没有的权限，Agent 都不能用。
- 审计（`op_audit`）同时记录 Agent 主体（`actor_party`）与被代理的人（`on_behalf_of`），以及 `trace_id`、`batch_id`（同一批调用）与 `approval_id`（经批准的调用）。

## 暴露等级

每个 Operation 声明一个暴露等级（代码 Operation 的 `@LsOperation(exposure = …)`，Pack 迁移的 `exposure:`，未声明为 `NONE`）。只有 QUERY、DRAFT、SIMULATE、APPROVAL_REQUIRED 会作为工具发布给 Agent，且须未命中永不暴露清单、处于已发布状态。

| 等级 | Agent 能做什么 |
| --- | --- |
| `NONE` | 不暴露（默认） |
| `QUERY` | 只读查询，按真人数据域过滤（如 `ls.core.Work.find`、`ls.core.KnowledgeDocument.ask`、`ls.core.Summary.context`） |
| `DRAFT` | 只能产生草稿；执行结果状态不是 DRAFT 时拒绝（`AGENT_DRAFT_REQUIRED`）。如登记异常、草拟摘要 |
| `SIMULATE` | 只能模拟执行：完整校验（输入、守卫、规则、权限）后回滚，正式执行返回 `AGENT_SIMULATE_ONLY`。推进类状态迁移默认为此等级 |
| `APPROVAL_REQUIRED` | 须真人逐次批准后执行一次（见下节）。如取消、生成整改工作 |
| `DENY` | 永不暴露。确认完成、退回、验证通过 / 不通过、驳回、摘要发布等 |

暴露等级变更须发布新版本（代码 Operation 提升 `version`）；工具清单与 Operation 注册表自动一致，降为 `DENY` 并发布后工具立即消失。

## 审批绑定

APPROVAL_REQUIRED 的调用先由 Agent 申请（MCP 工具 `ls.agent.requestApproval`），真人在「Agent 审批」页批准或驳回。批准记录绑定：

- Operation 编码、输入摘要（规范化 JSON 的 SHA-256）、目标对象与业务版本；
- 批准人、有效期（1～1440 分钟，页面提供 10 分钟到 4 小时）与一次性 ID。

输入或业务版本变化、过期、重复使用都会被拒绝（`AGENT_APPROVAL_*`）。批准人须对该 Operation 本身有权限。

## 永不暴露清单

下列能力无论声明何种暴露等级都不会出现在工具清单中，Agent 调用一律拒绝（`AGENT_TOOL_DENIED`，提示须由真人执行）并审计。清单的机器可读版本是 `ls-mom-modules/ls-mom-action/src/main/resources/agent-denylist.txt`；本表必须与之一致（CI 与单元测试校验）。匹配写法：Operation 编码通配（`*` 匹配任意字符）、`guard:<守卫编码>`、`perm:<权限点通配>`。

<!-- deny-list:start -->
| 类别 | 匹配 | 说明 |
| --- | --- | --- |
| APPROVAL | `*.approve` | 代审批：批准 |
| APPROVAL | `*.reject` | 代审批：驳回 |
| APPROVAL | `*.audit` | 代审批：审核 |
| APPROVAL | `*.confirm` | 代审批：确认完成 |
| APPROVAL | `*.rejectConfirm` | 代审批：退回 |
| APPROVAL | `*.accept` | 代审批：验收、接班 |
| APPROVAL | `*.returnBack` | 代审批：退回 |
| APPROVAL | `*.return` | 代审批：退回 |
| APPROVAL | `*.publish` | 代审批：发布（交接班、摘要、咨询总结等） |
| APPROVAL | `*.gradeEssay` | 代审批：主观题判分 |
| DUAL_SIGN | `guard:ls.std.dualSign.exclusive` | 双签：带双签守卫的任何迁移 |
| DUAL_SIGN | `*.countersign` | 双签：会签 |
| QUALITY_FINAL | `*.verifyPass` | 质量终判：验证通过 |
| QUALITY_FINAL | `*.verifyFail` | 质量终判：验证不通过 |
| QUALITY_FINAL | `ls.quality.*.close` | 质量终判：不合格品关闭 |
| QUALITY_FINAL | `*.judge` | 质量终判：判定 |
| QUALITY_FINAL | `*.disposition` | 质量终判：处置 |
| GENERIC_DELETE | `*.delete` | 通用删除 |
| GENERIC_DELETE | `*.remove` | 通用删除 |
| GENERIC_DELETE | `perm:crud:*:remove` | 通用删除：通用 CRUD 删除权限 |
| ARBITRARY_INVOKE | `*.invoke` | 任意方法调用 |
| ARBITRARY_INVOKE | `*.execute` | 任意方法调用 |
| ARBITRARY_INVOKE | `*.script` | 任意脚本 |
| ARBITRARY_INVOKE | `*.sql` | 任意 SQL |
| DCS_WRITE | `ls.dcs.*` | DCS 写入：DCS 命名空间下的任何 Operation |
| DCS_WRITE | `*.writeValue` | DCS 写入：写值 |
| DCS_WRITE | `*.setpoint` | DCS 写入：设定值 |
| DCS_WRITE | `perm:ls:dcs:write*` | DCS 写入：写权限 |
<!-- deny-list:end -->

此外，以下能力不经 Operation 网关、Agent 无从调用：ChangeSet 发布（`CHANGESET_RELEASE_HUMAN_ONLY`）、知识库文档发布、Pack 安装与升级、用户与角色授权、Agent 身份与 Key 管理。

## 草稿、摘要与知识问答

- **摘要**（`ls.core.Summary`）：Agent 先取上下文（`ls.core.Summary.context`，返回带引用的事实：异常、交接班、DCS 报警等），再草拟（`ls.core.Summary.draft`）。每个要点至少引用一条上下文中的记录，引用不在上下文中即拒绝；草稿由有确认权限的真人在「摘要确认」页发布或作废。摘要不回写被摘要的对象。咨询总结由咨询 Pack 的同类机制提供（观点须引用来源意见，发布限真人）。
- **知识问答**（`ls.core.KnowledgeDocument.ask`）：业务数字只来自已发布指标（返回数值、指标编码与版本、可钻取的记录引用）；文档问答只检索调用者可见的、当前已发布版本的分块，权限在检索 SQL 中先过滤、返回前再复核；回答的每句话都标注引用。修订稿在发布前不可检索。

## 门禁

| 检查 | 位置 |
| --- | --- |
| 命中永不暴露清单的 Operation 不得声明 DENY / NONE 以外的暴露等级；本文清单与 `agent-denylist.txt` 一致 | `scripts/ci/scan-agent-exposure.py`（CI：backend.yml） |
| 本文清单与运行时加载的清单一致 | `AgentBoundaryDocTest`（ls-mom-action） |
| DCS 写入能力不得出现在 Operation 与 Agent 工具中 | `scripts/ci/scan-dcs-write.py` |
| 网关执行前检查（清单、暴露等级、模拟、审批）与执行后检查（草稿） | `AgentPolicy`（`OperationGateway.run`） |

## 变更清单

1. 修改 `agent-denylist.txt` 与本文的表格（两处须同时改）；
2. 调整命中新条目的 Operation 的暴露等级为 `DENY`（代码 Operation 同时提升 `version`）；
3. 本地运行 `python3 scripts/ci/scan-agent-exposure.py` 与 ls-mom-action 的测试。
