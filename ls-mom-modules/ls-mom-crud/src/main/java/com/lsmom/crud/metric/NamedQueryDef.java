package com.lsmom.crud.metric;

import java.util.List;

/**
 * 命名 Query 定义（只能由研发在代码中以 Spring Bean 提供，客户配置不能提交 SQL）。
 * <ul>
 *   <li>{@code sql}：只读 select/with 语句；参数写作 {@code :name}；必须包含数据域占位 {@code {{scope:t.org_unit_id}}}
 *       （可加本人列 {@code {{scope:t.org_unit_id|t.created_by_party}}}），执行时替换为调用者数据域条件；
 *       {@code {{actor}}} 替换为调用者本人的 Party id（"我的待办"类查询）</li>
 *   <li>{@code paramSchema}：参数 JSON Schema（2020-12），调用前校验</li>
 *   <li>{@code examples}：至少一组样例参数（登记时用 EXPLAIN 验证可执行），即命名 Query 的测试</li>
 * </ul>
 */
public record NamedQueryDef(String code, String name, String description, String paramSchema, String sql, int timeoutMs,
                            String permission, List<String> examples) {
}
