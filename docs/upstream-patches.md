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

## 合并上游更新的方法

```bash
git fetch upstream --tags
git diff v5.6.2 <新tag> -- ruoyi-admin ruoyi-common ruoyi-modules ruoyi-extend pom.xml > upstream.diff
# 逐项对照本清单检查冲突后再应用
```
