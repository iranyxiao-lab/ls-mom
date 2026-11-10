# 首页卡片

首页由注册的卡片组成，按角色的默认布局显示，每张卡片都能一键进入对应列表。

## 卡片

| 编码 | 卡片 | 数据 |
|---|---|---|
| `myReviews` | 我的待办 | 命名 Query `ls.ui.homeMyReviews`（`{{actor}}` 为当前用户） |
| `teamWork` | 今日工作 | `ls.ui.homeTeamWork`（按数据域） |
| `openIssues` | 未关闭异常 | `ls.ui.homeOpenIssues` |
| `onTimeRate` | 按时完成率 | Metric |
| `quickLinks` | 快捷入口 | 当前用户可见的菜单 |

用户没有某张卡片所需的权限或菜单时，这张卡片不显示，也不能添加。

新增卡片的写法是在前端调用 `registerHomeCard({ code, title, description, component, permission?, menu? })`（参见 `src/home/builtin.ts`），并把 `code` 加进 `HomeService` 的卡片白名单。

## 布局

- **角色默认**：存放在 `meta_view` 中（`view_type='HOME'`，编码 `home.<roleKey>`），没有角色布局时用 `home.default`。用户有多个角色时，取第一个配置了布局的角色。
- **个人调整**：用户在首页点「调整首页」，可以上移、下移、隐藏或添加卡片。布局保存在服务端（`ui_user_pref.home`），换浏览器登录后保持不变。点「恢复默认」会回到角色布局。
- **设为角色默认**：拥有 `lsmom:ui:home:manage` 权限的用户可以把当前布局设为某个角色的默认布局。保存时会生成新版本，旧版本作废。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ui/home` | 当前用户的布局（`source` 为 personal、role 或 default） |
| PUT / DELETE | `/api/ui/home/mine` | 保存个人布局 / 恢复默认 |
| GET | `/api/ui/home/roles` | 可设置布局的角色（需要 `lsmom:ui:home:manage`） |
| PUT | `/api/ui/home/roles/{roleKey}` | 设为该角色的默认布局（需要 `lsmom:ui:home:manage`） |

演示种子 `db/seed/demo/V13__demo_home_layouts.sql` 为班组长、车间主任和质检员配置了默认布局。端到端测试见 `tests/ui/app/home.spec.ts`。
