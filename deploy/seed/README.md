# 演示种子数据

`LSMOM_SEED_DEMO_ENABLED=true`（本地 Compose 默认）时，应用启动会在核心迁移之后执行
`ls-mom-modules/ls-mom-core/src/main/resources/db/seed/demo/` 下的种子脚本。
执行历史记在 `flyway_seed_history` 表，每个脚本只执行一次；脚本本身也可重复执行，不会产生重复数据。

**仅限本地与验收环境。生产部署必须把 `LSMOM_SEED_DEMO_ENABLED` 设为 `false`，并修改上游默认管理员口令。**

## 组织

LS 演示工厂 → 一车间（甲班、乙班）、二车间（丙班）、设备科、质量科

## 账号

| 账号 | 角色 | 所属 | 口令 |
| --- | --- | --- | --- |
| admin | 超级管理员（上游默认） | — | admin123 |
| demo_leader | 车间主任 | 一车间 | Lsmom@2026 |
| demo_team | 班组长 | 一车间甲班 | Lsmom@2026 |
| demo_operator | 操作工 | 一车间甲班 | Lsmom@2026 |
| demo_asset | 设备员 | 设备科 | Lsmom@2026 |
| demo_quality | 质检员 | 质量科 | Lsmom@2026 |
| demo_implementer | 实施顾问 | LS 演示工厂 | Lsmom@2026 |

演示角色目前只有登录权限；各业务菜单随对应 Pack 安装后授予。

## 字典

- `ls_shift` 班次：早班 DAY、中班 EVENING、夜班 NIGHT
- `ls_work_type` 工作类型：PRODUCTION、MAINTENANCE、INSPECTION、IMPROVEMENT、OTHER

## 重新执行

```bash
deploy/scripts/seed.sh
```
