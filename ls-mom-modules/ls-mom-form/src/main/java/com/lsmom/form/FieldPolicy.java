package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 节点字段策略合并（与前端 {@code resolveFieldPolicy} 同一算法，共享用例 packs/_shared/field-policy）。
 * <p>优先级：显式 editable ＞ readonly/hidden（显式或通配 *）＞ 表单默认。
 * 计算字段永远只读；required 使字段可见；不可见字段不可编辑。子表列键为 {@code 子表.列}，受 {@code 子表} 整体策略约束（显式列 editable 例外）。</p>
 */
public final class FieldPolicy {

    public record Access(boolean visible, boolean editable, boolean required) {
    }

    private FieldPolicy() {
    }

    /** 返回 字段键（含子表键与 子表.列）→ 访问控制。policy 为节点策略（可为 null，表示表单默认）。 */
    public static Map<String, Access> resolve(JsonNode form, JsonNode policy) {
        Map<String, Access> out = new LinkedHashMap<>();
        Set<String> ro = set(policy, "readonly");
        Set<String> ed = set(policy, "editable");
        Set<String> hi = set(policy, "hidden");
        Set<String> rq = set(policy, "required");
        for (JsonNode f : form.path("fields")) {
            String key = f.path("key").asText();
            out.put(key, access(f, key, null, ro, ed, hi, rq));
        }
        for (JsonNode s : form.path("subTables")) {
            String sk = s.path("key").asText();
            Access table = access(s, sk, null, ro, ed, hi, rq);
            out.put(sk, table);
            for (JsonNode f : s.path("fields")) {
                String key = sk + "." + f.path("key").asText();
                Access col = access(f, key, sk, ro, ed, hi, rq);
                // 子表整体不可见时列跟随；子表只读时列也只读，除非该列被显式 editable
                boolean visible = col.visible() && table.visible();
                boolean editable = visible && col.editable() && (table.editable() || ed.contains(key));
                out.put(key, new Access(visible, editable, col.required() && visible));
            }
        }
        return out;
    }

    private static Access access(JsonNode f, String key, String parent, Set<String> ro, Set<String> ed, Set<String> hi, Set<String> rq) {
        boolean computed = f.path("widget").asText().equals("computed") || f.has("computed");
        boolean visible = !f.path("hidden").asBoolean(false);
        boolean editable = !f.path("readonly").asBoolean(false) && !computed;
        boolean required = f.path("required").asBoolean(false);
        if (hit(ro, key, parent)) editable = false;
        if (hit(hi, key, parent)) visible = false;
        if (explicit(ed, key, parent) && !computed) {
            editable = true;
            visible = true;
        }
        if (hit(rq, key, parent)) {
            required = true;
            visible = true;
        }
        if (!visible) {
            editable = false;
            required = false;
        }
        return new Access(visible, editable, required);
    }

    /** 显式或通配命中（子表列也被子表键命中）。 */
    private static boolean hit(Set<String> set, String key, String parent) {
        return set.contains("*") || set.contains(key) || (parent != null && set.contains(parent));
    }

    /** editable 只认显式键，不认通配（"*" 等价于表单默认，不能把只读字段放开）。 */
    private static boolean explicit(Set<String> set, String key, String parent) {
        return set.contains(key) || (parent != null && set.contains(parent));
    }

    private static Set<String> set(JsonNode policy, String kind) {
        Set<String> s = new HashSet<>();
        if (policy != null) policy.path(kind).forEach(n -> s.add(n.asText()));
        return s;
    }
}
