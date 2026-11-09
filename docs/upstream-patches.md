# 上游来源与补丁清单

LS-MOM 以未修改的上游快照为起点（提交 `80a0038`），之后对上游文件的所有修改都必须登记在本文件，便于以后合并上游更新。

## 上游版本

| 组件 | 仓库 | Tag | 上游提交 | 关键版本 |
| --- | --- | --- | --- | --- |
| 后端 | https://github.com/dromara/RuoYi-Vue-Plus（远程名 `upstream`） | `v5.6.2` | `8136a0191a2258c0e1b36a8146a1c5ebc070c139` | Spring Boot 3.5.15、Sa-Token 1.45.0、MyBatis-Plus 3.5.16 |
| 前端 | https://gitee.com/JavaLionLi/plus-ui（远程名 `upstream-ui`） | `v5.6.2-v2.6.2` | `d0d451967676707021b9857df529c395b27e90a7` | Vue 3.5.30、Element Plus 2.13.5、Pinia 3、vue-router 5 |

选择 5.x 而非 6.x 的原因：6.x 已升级到 Spring Boot 4，而设计基线要求 Spring Boot 3 并嵌入 Flowable 7（见 `openspec/changes/bootstrap-ls-mom-mvp/design.md` D1）。

## 补丁清单

| # | 文件／范围 | 修改 | 原因 | 关联任务 |
| --- | --- | --- | --- | --- |
| 1 | `README.md`、`LICENSE` | 上游文件移至 `docs/upstream/`、`licenses/`，根目录改为 LS-MOM 自己的 README、LICENSE、NOTICE | 产品自有说明，同时保留 MIT 声明 | 1.3 |
| 2 | `.gitignore` | 在上游内容后追加 LS-MOM 段（前端、密钥、运行数据） | 防止提交凭据与构建产物 | 1.1 |
| 3 | `ruoyi-modules/ruoyi-workflow/`、`pom.xml`、`ruoyi-modules/pom.xml`、`ruoyi-admin/pom.xml` | 删除 Warm-Flow 工作流模块及 `warm-flow.*` 依赖管理 | 流程引擎改用 Flowable 7，避免双引擎（D2） | 2.1 |
| 4 | `ruoyi-admin/src/main/resources/application.yml` | 删除 `warm-flow` 配置段与相关白名单/XSS 排除路径；`tenant.enable=false` | 去除 Warm-Flow；单客户单库关闭多租户（D1） | 2.1 |
| 5 | `ruoyi-admin/src/main/resources/application-dev.yml` | 主数据源改为 PostgreSQL（`DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`），Redis 地址与密码改为环境变量，监控客户端与 SnailJob 默认关闭（`MONITOR_ENABLED`、`SNAIL_JOB_ENABLED`） | 默认数据库为 PostgreSQL 16，Compose 通过环境变量注入 | 2.1 |
| 6 | `ruoyi-admin/pom.xml` | 启用 `org.postgresql:postgresql` 驱动 | 同上 | 2.1 |
| 7 | `script/sql/**/*workflow*`、`script/leave/` | 删除各数据库的工作流建表与菜单脚本、请假示例 | 同补丁 3 | 2.1 |
| 8 | `plus-ui/src/views/workflow/`、`plus-ui/src/api/workflow/`、`plus-ui/src/components/Process/` | 删除工作流页面、接口与审批组件 | 同补丁 3；LS-MOM 协同中心另行实现 | 2.1 |
| 9 | `pom.xml`、`ruoyi-admin/pom.xml` | 根 pom 增加 `ls-mom-modules` 模块；admin 依赖全部 LS-MOM 模块与标准 Pack | 接入自研模块；LS-MOM 组件经 `com.lsmom` 自动配置装配，不改上游启动类 | 2.2 |
| 10 | `ruoyi-admin/src/main/resources/application.yml` | 追加 `spring.flyway`（核心迁移目录、V1 基线）与 `lsmom.schema.drift-mode: warn` | 表结构只经版本化迁移变更，启动时检测漂移 | 2.4 |
| 11 | `ruoyi-admin/src/main/resources/application-prod.yml` | 数据源改为 PostgreSQL 且连接参数全部来自环境变量；Redis 地址、密码改为环境变量；监控与 SnailJob 默认关闭；`lsmom.schema.drift-mode: fail` | 生产不保留默认口令；生产发现漂移拒绝启动 | 2.4 |

## 合并上游更新的方法

```bash
git fetch upstream --tags
git diff v5.6.2 <新tag> -- ruoyi-admin ruoyi-common ruoyi-modules ruoyi-extend pom.xml > upstream.diff
# 逐项对照本清单检查冲突后再应用
```
