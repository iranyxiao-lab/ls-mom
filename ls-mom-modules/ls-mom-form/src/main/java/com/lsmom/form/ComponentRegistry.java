package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 表单组件注册表。组件实现随 Pack 经代码评审与测试发布，这里登记版本、输入输出、权限、兼容的 FormSchema 版本与测试位置；
 * 设计器只能选择注册项，注册内容同样经 {@link ScriptGuard} 检查，不接受任何代码。
 */
@Service
public class ComponentRegistry {

    /** 当前 FormSchema 结构版本。 */
    public static final int FORM_SCHEMA_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public ComponentRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Component(String code, String version, String displayName, String description, JsonNode inputs, JsonNode outputs,
                            String permission, int compatFrom, Integer compatTo, String testRef, String status) {
        public boolean compatibleWith(int schemaVersion) {
            return schemaVersion >= compatFrom && (compatTo == null || schemaVersion <= compatTo);
        }
    }

    public void register(Component c) {
        List<ViewIssue> issues = new ArrayList<>();
        ScriptGuard.scan(JSON.valueToTree(c), "", issues);
        if (c.code() == null || !c.code().matches("^[a-z][a-z0-9]*(\\.[A-Za-z][A-Za-z0-9]*)+$")) {
            issues.add(new ViewIssue("/code", "COMPONENT_INVALID", "组件编码不合法：" + c.code()));
        }
        if (c.version() == null || !c.version().matches("^[0-9]+\\.[0-9]+\\.[0-9]+$")) {
            issues.add(new ViewIssue("/version", "COMPONENT_INVALID", "组件版本须为语义化版本"));
        }
        if (c.testRef() == null || c.testRef().isBlank()) issues.add(new ViewIssue("/testRef", "COMPONENT_INVALID", "组件必须登记测试"));
        if (!issues.isEmpty()) throw new LsBizException("COMPONENT_REJECTED", "组件注册失败：" + issues.get(0).message(), Map.of("issues", issues));
        jdbc.update("""
            insert into form_component (code, version, display_name, description, inputs, outputs, permission, compat_from, compat_to, test_ref)
            values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            on conflict (code, version) do nothing""",
            c.code(), c.version(), c.displayName(), c.description(), String.valueOf(c.inputs() == null ? "[]" : c.inputs()),
            String.valueOf(c.outputs() == null ? "[]" : c.outputs()), c.permission(), c.compatFrom(), c.compatTo(), c.testRef());
    }

    public Optional<Component> find(String code, String version) {
        return jdbc.query("select * from form_component where code = ? and version = ?", (rs, i) -> {
            try {
                return new Component(rs.getString("code"), rs.getString("version"), rs.getString("display_name"), rs.getString("description"),
                    JSON.readTree(rs.getString("inputs")), JSON.readTree(rs.getString("outputs")), rs.getString("permission"),
                    rs.getInt("compat_from"), (Integer) rs.getObject("compat_to"), rs.getString("test_ref"), rs.getString("status"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, code, version).stream().findFirst();
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
            select code, version, display_name, description, inputs::text as inputs, outputs::text as outputs, permission, compat_from,
                   compat_to, status from form_component where status = 'ACTIVE' order by code, version""");
    }
}
