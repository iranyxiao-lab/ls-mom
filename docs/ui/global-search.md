# 全局搜索

命令面板的搜索走后端读模型 `ui_search_index`（ls-mom-ui 模块）。

- **收录对象**：工作（`ls.core.Work`）、在岗人员（`ls.core.Party`，员工与外协）、全部已发布台账的主表记录。
  台账的编号取唯一文本字段（或名为 `code` 的字段），标题取带 `label` 的字段（或 `name` / `title` / `subject`）；设置了 `readPermission` 的字段不进入索引。
- **更新**：订阅 Outbox（`ui.search-index`），新增、修改、复制、删除与 Operation 事件到达后按对象当前状态整行重建；
  启动时与每 30 分钟补建一次（覆盖没有事件的变更，如人员调动），并清理已删除对象的行。
- **匹配**：编号、标题、标题全拼、首字母（pinyin4j），小写后做包含匹配与 pg_trgm 相似度匹配。
- **排序**：编号完全一致 → 编号前缀 → 标题前缀 → 相似度。
- **权限**：命名 Query `ls.ui.globalSearch` 注入调用者数据域（组织或本人创建）；台账类型还要求 `crud:<Concept>:list` 权限。
- **接口**：`GET /api/ui/search?q=&limit=`（最多 50 条），返回 `object_type`、`object_id`、`code`、`title`、`subtitle`、`rank`、`score`。
- **测试**：`GlobalSearchTest`（集成），`tests/e2e/global-search.e2e.mjs`（部署环境）。
