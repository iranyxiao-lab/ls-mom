# 通用台账（CRUD）

单表、主子表和简单协同台账只靠配置获得受控物理表、增删改查、查询、导入导出、权限、并发与时间轴，不写 Java；正式动作（提交、审核、退回、关闭……）仍只能经命名 Operation。

## 发布与表结构

- 台账 Concept 发布（Pack 安装器 `POST /api/ledger/publish`，需 `lsmom:pack:install`）时，`DdlPlanner` 比较新旧定义生成 DDL，登记到 `meta_ddl_script`，由 `PackMigrator` 经 Flyway（历史表 `pack_schema_history`）执行；`GET /api/ledger/{concept}/migrations` 查看迁移记录。不使用 EAV 或通用记录大表。
- 主表 `c_<命名空间>_<对象>`：保留字段 `id`、`org_id`、`status`、`created_by`、`created_at`、`updated_by`、`updated_at`、`version`、`del_flag`、`schema_version`、`ext_attrs`。子表（`parentConcept` 指向主表）：`id`、`parent_id`（外键）、`line_no`、`ext_attrs`。
- 新字段默认生成显式列；`props.queryable/sortable/indexed` 建索引，`unique` 建部分唯一索引；只有 `props.ext=true` 的非关键补充信息进入 `ext_attrs`。
- 改类型、删列、改名是破坏性变更：直接发布被拒（`BREAKING_CHANGE_REQUIRES_REVIEW`）。改类型经 `POST /api/ledger/{concept}/fields/{field}/type-change` 生成待评审变更集与迁移脚本（新列 → 转换 → 替换），他人评审（`/changeset/{id}/review`，提交人不能自审）后由 Pack 安装器执行（`/changeset/{id}/apply`）。

字段属性（`props`）：`queryable`、`sortable`、`indexed`、`unique`、`maxLength`、`precision`、`scale`、`minimum`、`maximum`、`pattern`、`dictType`（RuoYi 字典）、`optionLabels`、`label`（作为引用显示名）、`readPermission`、`writePermission`、`ext`。

## 接口（`/api/crud/{concept}`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/schema` | ListSchema：字段、筛选/排序白名单、子表、绑定的 Operation（含起始状态）、按钮权限 |
| POST | `/query` | 列表：`{filters:[{field,op,value}], sort:[{field,dir}], page, size, expand:[引用字段]}` |
| POST | `/aggregate` | 分组聚合：`groupBy` + `aggregates:[{fn,field,alias}]` |
| GET | `/{id}` | 详情：主表 + 子表行（键为子表名首字母小写，如 `loanLine`）+ 字典翻译 + 引用展开 + 可执行动作 |
| POST | `/` | 新增（主子表单事务）；`Idempotency-Key` 防重复提交 |
| PUT | `/{id}?version=` | 部分更新（只改报文中的字段；出现子表键时整体替换该子表） |
| POST | `/{id}/copy` | 复制为新草稿（唯一字段清空） |
| DELETE | `/{id}?version=` | 逻辑删除 |
| GET/POST | `/import/template`、`/import`、`/import/{batchId}/confirm` | Excel 导入：模板、预检（错误行报告）、确认（只导入正确行） |
| POST | `/export`；GET `/api/crud/export/{jobId}`、`/download` | 异步导出 |

功能权限：`crud:<Concept>:list|query|add|edit|remove|export|import`。

## 服务端强制

- **白名单查询**：字段须开放 `queryable`/`sortable`，操作符取 `eq ne gt ge lt le like startsWith in notIn between isNull notNull`，聚合取 `count sum avg min max`，值一律参数绑定；关联筛选只支持 `引用字段.被引用字段`，被引用字段须开放查询、调用者须有被引用台账的查看权限，并按被引用对象的数据域过滤。违规返回 400 `QUERY_REJECTED`。
- **数据域**：行按 `org_id`（组织范围）或 `created_by`（本人）过滤；越域读取、编辑、删除返回无权限；新增只能建在自己数据域内的组织；引用字段只能指向可见记录。
- **字段权限**：无 `readPermission` 的字段不返回、不能筛选；无 `writePermission` 的字段不能写入。
- **校验**：必填、类型、长度、范围、枚举、字典、正则；子表错误定位到行（`loanLine[2].tool` → "第 3 行：工器具必填"），整单不落库。
- **正式动作分流**：只有 `DRAFT` 可通用编辑/删除；`status` 只能经 Operation 修改；状态字段与 C2 对象由 `GenericWritePolicy` 拦截。台账可声明状态机，迁移经 `ManagedStateStore` 写入状态与版本。
- **并发与审计**：乐观锁（`version`）；同一幂等键的重复请求返回首次结果；每次写入记 `op_audit`，并以 `crud.created/updated/copied/deleted` 事件进入业务时间轴（`updated` 含字段级 `changes`）。
- **Agent 禁用**：Agent 身份调用任何通用接口返回无权限（`reason=AGENT_FORBIDDEN`）并审计。
- **导入导出限额**：`lsmom.crud.import-max-rows`（默认 5000）、`export-max-rows`（默认 10000，超出拒绝并审计）、`max-page-size`（默认 200）；每人同时最多 2 个导出任务；导出文件存对象存储，只有创建人可下载，下载审计。

## 前端

`plus-ui/src/components/SchemaTable`：按 ListSchema 生成列、筛选、排序与分页，行/批量动作绑定已发布 Operation（经 `/api/op` 执行），导出提交异步任务。页面路由 `/lsmom/ledger/<Concept 编码>`，菜单可直接指向。
