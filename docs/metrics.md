# 指标与命名 Query

看板、报表和外部系统只能通过两种入口取数：**指标（Metric）** 和 **命名 Query**。两者都是预先登记、可审计的定义，界面和接口都不接受任意 SQL。

代码在 `ls-mom-crud` 的 `com.lsmom.crud.metric` 包，表结构见 `db/migration/core/V61__metric.sql`（表 `meta_metric`、`meta_named_query`）。

## 指标

### 定义

```json
{
  "code": "ls.core.issueCount",
  "name": "异常数",
  "concept": "ls.core.Issue",
  "description": "登记的异常（不含草稿与已取消），按登记时间统计",
  "unit": "条",
  "measure": { "fn": "count" },
  "filters": [{ "field": "status", "op": "notIn", "value": ["DRAFT", "CANCELLED"] }],
  "timeField": "createdAt",
  "grains": ["DAY", "WEEK", "MONTH"],
  "dimensions": ["status", "severity", "issueType", "orgUnitId"],
  "drillColumns": ["code", "title", "severity", "status", "orgUnitId", "createdAt"]
}
```

| 字段 | 说明 |
|---|---|
| `code` | `<命名空间>.<名称>`，与 Operation 编码同规则 |
| `concept` | 统计的业务对象；核心对象和托管对象都可以 |
| `measure.fn` | `count` / `sum` / `avg` / `min` / `max` / `ratio`。`sum`、`avg` 只能用于数值字段；`ratio` 用 `numerator` 条件计算分子，分母为满足 `filters` 的全部记录 |
| `filters` | 口径条件，`op` 取 `eq ne gt ge lt le in notIn isNull notNull`；`valueField` 表示与另一字段比较（如 `actualEnd le plannedEnd`） |
| `timeField` | 时间范围与趋势粒度所依据的字段；声明了 `grains` 时必填 |
| `grains` / `dimensions` | 允许的时间粒度和分组维度，查询与看板只能在这些里选 |
| `permission` | 可选，查询所需权限；为空时只按数据域过滤 |
| `drillColumns` | 钻取明细显示的列 |

### 口径版本

- 发布接口是 `POST /api/metrics/publish`，需要权限 `lsmom:metric:design`；校验接口是 `POST /api/metrics/validate`。
- **口径哈希**只由 `concept`、`measure`、排序后的 `filters` 和 `timeField` 计算。名称、说明、维度等展示信息变化，不算口径变化。
- 定义没有变化时重复发布不产生新版本。口径变化时产生新版本，旧版本标为 `RETIRED`，查询时可以用 `version` 指定历史版本。
- 已发布的**另一个**指标口径哈希相同时，发布会被 `METRIC_DUPLICATE` 拒绝，防止同一口径出现两个名字。

校验错误码：`CODE_INVALID`、`NAME_REQUIRED`、`CONCEPT_NOT_FOUND`、`MEASURE_INVALID`、`FIELD_NOT_FOUND`、`FIELD_NOT_NUMERIC`、`NUMERATOR_REQUIRED`、`OP_INVALID`、`CONDITION_INVALID`、`TIME_FIELD_INVALID`、`TIME_FIELD_REQUIRED`、`GRAIN_INVALID`、`DIMENSION_INVALID`、`METRIC_DUPLICATE`。

### 查询与钻取

```http
POST /api/metrics/ls.core.issueCount/query
{ "from": "2026-11-01", "to": "2026-11-30", "grain": "WEEK", "groupBy": ["severity"] }
```

返回每个时间桶、每组维度一行：`{ bucket, severity, value }`。`ratio` 指标的行还带 `numerator` 和 `denominator`，`value` 保留 4 位小数。最多返回 1000 组。

```http
POST /api/metrics/ls.core.issueCount/drill
{ "from": "...", "to": "...", "grain": "WEEK", "bucket": "2026-11-09", "dims": { "severity": "HIGH" }, "page": 1, "size": 50 }
```

钻取返回构成该数值的明细记录：`total` 与指标值相等，每页最多 500 行。`ratio` 指标传 `numeratorOnly: true` 只看分子记录。

查询和钻取都套用当前用户的数据域：托管对象按 `orgId`、核心对象按 `orgUnitId` 过滤；「仅本人」时按创建人或登记人过滤。托管对象自动排除已删除记录。参数不合法时返回 `METRIC_QUERY_INVALID`。

### 内置指标

| 编码 | 含义 |
|---|---|
| `ls.core.issueCount` | 异常数（不含草稿、已取消），按登记时间 |
| `ls.core.workOnTimeRate` | 按期完成率：已完成且有计划结束时间的工作中，实际结束不晚于计划结束的比例，按计划结束时间 |

## 命名 Query

指标表达不了的统计（多表关联、自定义分组）用命名 Query。定义由代码或 Pack 提供，启动时登记，不能在界面上编写。

```java
new NamedQueryDef("ls.core.openIssuesByOrg", "各组织未关闭异常", "说明",
    paramSchemaJson,           // JSON Schema，type 必须是 object
    """
    select o.id as org_unit_id, count(*) as open_count
      from core_issue t join core_org_unit o on o.id = t.org_unit_id
     where (cast(:severity as varchar) is null or t.severity = :severity)
       and {{scope:t.org_unit_id|t.reporter_party}}
     group by o.id""",
    5000,                      // 超时毫秒
    null,                      // 所需权限，可为空
    List.of("{}", "{\"severity\":\"HIGH\"}"));  // 示例参数
```

登记时检查：

- 只能是单条 `select` / `with` 语句，不能含 `;`、写操作关键字或危险函数。
- 必须包含数据域占位符 `{{scope:表别名.组织列}}` 或 `{{scope:表别名.组织列|表别名.本人列}}`，执行时替换为当前用户的数据域条件。
- 示例参数必须通过参数 Schema 校验，并逐个 `EXPLAIN` 成功。
- SQL 或 Schema 变化时版本号加 1。

执行：`POST /api/q/{queryCode}`，请求体就是参数对象。参数先按 Schema 校验，再在只读事务中执行，并设置 `statement_timeout`。最多返回 1000 行，超时返回 `QUERY_TIMEOUT`。`GET /api/q` 列出已登记的命名 Query 及参数 Schema。

业务 Pack 声明 `NamedQueryDef` 类型的 Bean 即可自动登记。

## 看板绑定

看板（`DASHBOARD` 视图，见 [form-schema.md](form-schema.md)）的组件只能写 `metric` 或 `query` 编码，不能写 SQL。发布时校验：

- 指标已发布，命名 Query 已登记。
- 维度和时间粒度在指标声明范围内；趋势图必须设粒度。
- `range` 取 `today`、`thisWeek`、`last7Days`、`thisMonth`、`last30Days` 或 `all`。

前端组件 `SchemaDashboard` 渲染指标卡、柱状图、折线图和表格；点击指标卡或图形打开钻取明细。页面路由是 `/lsmom/dashboard/:code`。

## 测试

| 测试 | 覆盖 |
|---|---|
| `MetricTest` | 口径版本、重复口径、数据域过滤、ratio、钻取数与指标值一致、命名 Query 的 SQL 拦截、参数校验、超时 |
| `DashboardBindingTest` | 看板写 SQL 被拒、引用未发布指标被拒、维度与粒度校验 |
| `schema-dashboard.spec.ts` | 看板渲染与钻取弹窗 |
| `tests/e2e/metrics-dashboard.e2e.mjs` | 部署环境：登记异常 → 看板数值与接口一致 → 钻取明细条数等于指标值 |
