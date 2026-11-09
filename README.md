# LS-MOM 工业底座

> Java 主干的轻制造运营、台账低代码、工作协同、工业语义与 LLM 扩展底座（MVP v2.1）

LS-MOM 面向以人工操作和班组协同为主的产线：生产工作安排、台账与检查表、填报审批、异常整改、设备点检与 DCS 报警关联、经营看板。它不要求先建 BOM、工艺路线或库存；先如实记录工作如何发生，再按风险逐步加约束。

## 核心原则

- **核心稳定，扩展活跃**：稳定核心只有 Party／Org／Work／Assignment／Report／Issue／Observation／Event／Document；行业与客户功能进 Pack。
- **业务状态唯一**：状态只在业务表，流程引擎（Flowable 7）只管人的审批任务，结果经命名 Operation 回调推进。
- **所有关键写入走 Operation**：通用 CRUD 只处理草稿与低风险台账；提交、审核、关闭、分派等受控动作必须经 Operation（权限、守卫、幂等、审计）。
- **LS FormSchema 是表单唯一契约**：开源设计器只是可替换的编辑器。
- **LLM 不直达生产**：生成物进入 ChangeSet、测试和人工评审；Agent 只能调用白名单 Operation，默认产出草稿。

## 技术栈

| 层 | 选择 |
| --- | --- |
| 后端 | JDK 21、Spring Boot 3.5、RuoYi-Vue-Plus 5.6.2（Sa-Token、MyBatis-Plus） |
| 流程 | Flowable 7（嵌入式，独立 `flowable` schema） |
| 数据 | PostgreSQL 16（含 pgvector）、Redis、SeaweedFS（S3 兼容对象存储）、Flyway |
| 前端 | Vue 3、TypeScript、Element Plus、UnoCSS（plus-ui 2.6.2） |
| 部署 | Docker Compose，单客户单库 |

## 仓库结构

```
ruoyi-admin/  ruoyi-common/  ruoyi-modules/  ruoyi-extend/   上游 RuoYi-Vue-Plus 5.6.2
ls-mom-modules/                                              LS-MOM 自研模块（core/meta/crud/form/flow/action/pack/ai/dcs/mcp/biz）
plus-ui/                                                     前端管理台
packs/                                                       标准 Pack 源资产
deploy/                                                      Docker Compose、种子数据、备份恢复脚本
docs/                                                        设计与开发文档（上游补丁清单见 docs/upstream-patches.md）
openspec/                                                    需求规划（OpenSpec change 与 spec）
```

## 快速启动

前置：Docker 与 Docker Compose v2。

```bash
cd deploy
docker compose up -d
```

首次启动会自动执行数据库迁移并安装演示种子数据。启动完成后访问 `http://localhost`，演示管理员账号见 `deploy/seed/README.md`。

本地开发：

```bash
# 后端（需要 JDK 21）
mvn -DskipTests package
# 前端
cd plus-ui && pnpm install && pnpm dev
```

## 文档

- 需求与规划：[openspec/changes/](openspec/changes/)
- 上游来源与补丁：[docs/upstream-patches.md](docs/upstream-patches.md)

## 致谢与第三方许可

本项目基于以下开源项目构建，并保留其许可声明（见 [licenses/](licenses/) 与 [NOTICE](NOTICE)）：

- [RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus)（MIT）— 后端基座
- [plus-ui](https://gitee.com/JavaLionLi/plus-ui)（MIT）— 前端管理台
