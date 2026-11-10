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
| 12 | `ruoyi-admin/src/main/resources/logback-plus.xml` | 控制台与文件日志格式加入 `[%X{traceId:-}]` | 全链路 trace_id 进入日志 | 2.5 |
| 13 | `ruoyi-admin/src/main/resources/application-dev.yml`、`application-prod.yml` | 监控账号改为 `${MONITOR_USERNAME:...}`、`${MONITOR_PASSWORD:...}` | `/actuator` Basic 认证账号可由部署环境注入，容器健康检查使用 | 3.1 |
| 14 | `plus-ui/pnpm-workspace.yaml`（新增） | `allowBuilds` 放行 esbuild、@parcel/watcher | pnpm 10+ 在 CI/容器中默认拒绝依赖安装脚本 | 3.1 |
| 15 | `ruoyi-admin/pom.xml` | 移除 `mysql-connector-j` | 只用 PostgreSQL；MySQL 驱动为 GPL-2.0（Universal FOSS Exception 不覆盖闭源分发），许可证门禁拒绝 | 3.3 |
| 16 | `plus-ui/package.json`、`plus-ui/vitest.config.ts`（新增） | 增加 `vitest`、`@vitest/browser-playwright`、`playwright` 与 `test`（无头）/`test:browser` 脚本 | 前端单元与组件测试在真实浏览器中无头运行 | 3.3 |
| 17 | `plus-ui/.gitignore` | 不再忽略 `pnpm-lock.yaml`，锁文件入库 | CI 与镜像构建使用 `--frozen-lockfile`，依赖可复现 | 3.3 |
| 18 | `ruoyi-admin/src/main/resources/application.yml` | `captcha.enable` 改为 `${CAPTCHA_ENABLED:true}` | 自动化验收环境可关闭登录验证码；默认与生产保持开启 | 3.4 |
| 19 | `ruoyi-admin/src/main/resources/application.yml` | springdoc 分组「5.工作流模块」改为「5.LS-MOM」（`com.lsmom`），`api-docs.version: openapi_3_1` | Warm-Flow 已移除；Operation 以 OpenAPI 3.1 描述 | 5.9 |
| 20 | `ruoyi-admin/src/main/resources/application.yml` | `spring.flyway.out-of-order: true` | 各模块按版本段共用历史表，低段新脚本可能晚于高段脚本发布 | 7.1 |
| 21 | `plus-ui/src/router/index.ts` | 新增隐藏路由 `/lsmom/ledger/:concept`（通用台账列表页） | 菜单可直接指向任一已发布台账 | 7.7 |
| 22 | `ruoyi-admin/src/main/resources/application.yml` | `xss.excludeUrls` 增加 `/api/form/**` | 上游过滤器会把 `<script>` 剥成纯文本后放行；视图接口改由 ScriptGuard 原样检查并拒绝（FORM-08） | 8.7 |
| 23 | `plus-ui/src/router/index.ts` | 新增隐藏路由 `/lsmom/form-designer/:code?`（表单设计器） | 设计器页面入口 | 8.6 |
| 24 | `plus-ui/pnpm-workspace.yaml` | `overrides` 固定 form-create 及其间接依赖版本；`core-js-pure` 安装脚本不放行 | 冻结设计器版本，依赖均发布满 14 天 | 8.5 |
| 25 | `plus-ui/index.html` | `<head>` 内联首屏主题脚本（明暗、密度、品牌色）与深色底色 | 首屏无闪白（modernize-ui-ux 设计系统） | UI 1.4 |
| 26 | `plus-ui/src/main.ts`、`plus-ui/uno.config.ts`、`plus-ui/vite/plugins/index.ts`、`plus-ui/vitest.config.ts` | 引入 `virtual:ls-tokens.css` 与 `src/design/base.css`、初始化主题；UnoCSS 主题改由设计令牌生成并提取 `.ts` 中的图标类；注册令牌生成插件 | 设计令牌唯一来源 | UI 1.1～1.2 |
| 27 | `plus-ui/src/utils/theme.ts` | `handleThemeStyle` 只设置品牌主色令牌 `--ls-color-primary`（RuoYi 默认色视为未设置），`--el-color-primary-*` 改由映射层派生 | 品牌换色统一走令牌 | UI 1.2 |
| 28 | `plus-ui/src/layout/components/Settings/index.vue` | 深色开关改为「明暗主题」（跟随系统／浅色／深色）与「界面密度」选择；预设色加入令牌默认主色 | 主题与密度由设计系统管理 | UI 1.4 |
| 29 | `plus-ui/src/views/index.vue`、`plus-ui/src/views/error/*.vue` | 样式中的十六进制颜色与裸 px 间距替换为设计令牌 | 业务页面只用令牌（stylelint 门禁） | UI 1.1 |
| 30 | `plus-ui/src/layout/components/{Breadcrumb,Navbar,TagsView}`、`Sidebar/{Logo,SidebarItem}.vue`、`plus-ui/src/components/{LangSelect,SizeSelect}`、`plus-ui/src/permission.ts`、`plus-ui/src/views/login.vue`、`plus-ui/index.html` | 无障碍：页面语言、菜单项角色（菜单项自身导航，不再包 `AppLink`）、头像与 Logo 替代文本、图标按钮名称、进度条名称、颜色改用令牌 | WCAG 2.1 AA 门禁（axe） | UI 2.2 |
| 31 | `plus-ui/src/main.ts`、`plus-ui/src/plugins/svgicon.ts`、`plus-ui/src/components/{UserSelect,RoleSelect}`、`plus-ui/src/views/tool/gen/index.vue`、`plus-ui/vite/plugins/index.ts` | 去掉全局注册的 vxe-table 与 highlight.js（改为用到的页面局部引入）；图标改为异步组件；Element Plus 经精简入口加载 | 首屏 JS gzip ≤400KB | UI 2.4 |
| 32 | `deploy/docker/nginx.conf` | 开启 `gzip_static`／`gzip`，带哈希资源长期缓存、入口页不缓存 | 上游部署模板未压缩静态资源 | UI 2.4 |
| 33 | `plus-ui/src/utils/auth.ts`、`plus-ui/src/api/login.ts` | `useStorage` 显式引入（单测不依赖自动引入）；租户列表同一会话缓存 5 分钟 | 租户列表按 IP 限流，刷新页面会触发「访问过于频繁」 | UI 2.5、3.1 |
| 34 | `plus-ui/src/layout/index.vue`、`Navbar.vue`、`Sidebar/index.vue`、`.env.*` | 三档断点（手机抽屉／平板收起／桌面按偏好），小屏上的展开不改写桌面偏好；侧栏与顶栏颜色由令牌驱动；去掉顶栏上游仓库与文档链接；非超级管理员不请求租户列表；产品名改为 LS-MOM | 应用外壳 | UI 3.1 |
| 35 | `plus-ui/src/utils/createCustomNameComponent.tsx`、`plus-ui/src/layout/components/TagsView/index.vue` | 路由页面包装组件统一保留滚动位置、切换页签时隐藏／恢复该页打开的对话框；关闭含未保存内容的页签（含批量关闭）先确认 | 多页签状态保留 | UI 3.2 |

## 生产前必须替换的上游密钥

上游仓库自带以下演示密钥，任何人都能从公开源码拿到。gitleaks 按指纹豁免了这些位置（`.gitleaksignore`），只是为了不阻塞 CI，**不代表可以在生产使用**。客户部署前必须替换，并通过环境变量或私有配置注入，不写回仓库：

| 位置 | 内容 | 替换方式 |
| --- | --- | --- |
| `application.yml` `api-decrypt.publicKey/privateKey` 与 `plus-ui/.env.production`、`.env.development` 的 `VITE_APP_RSA_PUBLIC_KEY/PRIVATE_KEY` | 接口加解密 RSA 密钥对（前后端成对） | 重新生成两对 RSA 密钥，后端与前端构建参数同步更新 |
| `application.yml` `sa-token.jwt-secret-key` 等签名密钥 | 令牌签名 | 生产随机生成 ≥32 字节 |
| `application-dev.yml`、`application-prod.yml` 的 `justauth` 各 `client-secret` | 第三方登录演示凭据 | 未启用的登录源删除配置；启用的使用客户自己的应用凭据 |
| `ruoyi-demo` 的 `TestDemoEncrypt` | 字段加密示例密钥 | 演示模块不在生产启用 |
| 演示种子中的管理员与演示用户口令 | 见 `deploy/seed/README.md` | 首次登录后立即修改，生产不加载演示种子（`LSMOM_SEED_DEMO_ENABLED=false`） |

`ruoyi-extend`（SnailJob 服务端、监控服务端）不随 LS-MOM 应用分发，未纳入许可证门禁；若要部署需单独评估（SnailJob 服务端会带入 MySQL 驱动）。

## 合并上游更新的方法

```bash
git fetch upstream --tags
git diff v5.6.2 <新tag> -- ruoyi-admin ruoyi-common ruoyi-modules ruoyi-extend pom.xml > upstream.diff
# 逐项对照本清单检查冲突后再应用
```
