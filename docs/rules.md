# 规则与守卫

业务约束按级别表达，每条约束都标注级别；L1 及以上在服务端阻断，L2 及以上只以后端求值为准（前端结论只做即时提示）。

| 级别 | 含义 | 实现 | 未满足时 |
| --- | --- | --- | --- |
| L0 | 提示 | CEL / 决策表（outcome=WARN） | 操作成功，`warnings` 返回提示 |
| L1 | 字段校验 | JSON Schema；条件必填等用 CEL 规则并设 `field` | 400 `VALIDATION_FAILED`，`details.fields` 定位字段 |
| L2 | 声明式守卫 | CEL、决策表 | 422 `GUARD_FAILED`，`details.reason` 为规则编码 |
| L3 | 代码守卫插件 | 实现 `Guard` 的 Java Bean，经评审随 Pack 发布 | 422 `GUARD_FAILED` |
| L4 | 外部权威 | 人工审批或外部系统 | 422 `GUARD_FAILED`，`reason=APPROVAL_REQUIRED` |

## 规则定义（`meta_rule`）

```json
{ "code": "ls.mfg.Work.assigneeQualified", "level": "L2", "kind": "CEL", "name": "分派人员资质有效",
  "message": "被分派人员资质已过期，不能承担高风险工作",
  "definition": { "when": "$model.priority >= 5", "assert": "size($context.qualification.expired) == 0",
                  "appliesTo": ["ls.core.Work.assign"], "facts": ["qualification"] } }
```

- `when`：前置条件（可选）；`assert`：必须为真；`field`：L1 时报错定位的字段。
- `appliesTo`：自动附加到这些 Operation（无需修改 Operation 定义）；也可以在 Operation/迁移的 `guards` 中写 `rule:<编码>`。
- `facts`：需要的跨对象事实，由 Java 实现的 `RuleFactProvider` 提供（如培训 Pack 的 `qualification`），规则只读事实。
- **决策表**（`kind=DECISION_TABLE`）：`{"hitPolicy":"FIRST|ALL","rows":[{"when":"…","output":{…}}],"default":{…}}`。输出带 `outcome`（PASS/WARN/BLOCK）与 `message` 时可作守卫，ALL 策略取最严格的命中行；不带时作为判定使用（`POST /api/rule/{code}/evaluate` 试算）。
- **代码守卫**（`kind=JAVA`）：`{"guard":"ls.std.dualSign.exclusive"}`，只能引用已注册的守卫，配置中不能内嵌代码。示例 `DualSignGuard`：执行人与复核人不能是同一人。

## 受限表达式（CEL 子集）

- 变量只有 `$model`（目标对象字段叠加本次输入，输入优先）和 `$context`（`actor.partyId/userId/type/name`、`now`、`operation`、`targetStatus` 与声明的事实）。
- 函数白名单：`size`、`matches`（RE2）、`startsWith`、`endsWith`、`contains`、`int`、`double`、`string`、`bool`，以及比较、逻辑、`in`、三元、下标、四则运算与 `has()`。
- 不支持：推导式宏（`all`/`exists`/`map`/`filter`，即循环）、`timestamp`/`duration` 等其他函数、消息构造、任意方法调用、网络或数据库访问。
- 上限：长度 2000 字符、200 个节点、嵌套 24 层；运行期成本 1000 个求值节点、100 ms。超限或求值出错按**规则失败**处理（L0 提示，其余阻断，`details.ruleError` 给出原因）。
- 数值：整数是 `int`，带小数的是 `double`，二者不能直接相加（写 `double($model.qty) * $model.price`），比较可以混用。
- 值为 null 的字段视为"未填写"：`has($model.x)` 为 false，直接访问报错。时间以 ISO 字符串表示，可按字典序比较。

## 发布校验

`POST /api/rule/{code}/{version}/publish` 前执行（`/validate` 只校验不发布）：

1. 表达式通过受限环境静态校验——非白名单函数报 `RULE_ILLEGAL_FUNCTION` 并给出函数名，超长超复杂报 `RULE_TOO_COMPLEX`，类型错误报 `RULE_INVALID`；
2. 代码守卫已注册、级别为 L3/L4；引用的事实提供者已注册；
3. **加严不放宽**：客户/Pack 规则不能把标准 Pack 声明为阻断的规则关闭（`disabled`）或降为 L0——无论以 `overrides` 覆盖还是以同编码新版本发布，均报 `RULE_RELAXATION_FORBIDDEN`。覆盖规则与被覆盖规则同时执行（叠加），所以覆盖只会更严。

## 前后端一致性

前端 `plus-ui/src/utils/cel`（@bufbuild/cel 0.6.1，Apache-2.0，与后端同一白名单、`$` 预处理与数值规则）用于表单即时提示。`packs/_shared/cel-conformance/*.json` 用例集由后端 `CelConformanceTest` 与前端 `tests/unit/cel-conformance.spec.ts` 同跑，CI 两端都执行；当前两端结果全部一致，未启用"前端仅提示"降级。类型检查只在后端发布时进行，已发布的规则不会在前端出现类型错误。新增用例时两端必须同时通过；若出现无法消除的差异，在用例中标注并把该类表达式在前端降级为仅提示。

核心标准规则：`ls.core.Work.plannedEndHint`（L0，创建或就绪时未填计划结束时间给出提示）。
