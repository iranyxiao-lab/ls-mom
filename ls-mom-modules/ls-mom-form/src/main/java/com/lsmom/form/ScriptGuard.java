package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 拒绝任何脚本内容：视图中不允许出现 HTML 脚本、事件处理器、函数体、箭头函数、eval 等。
 * CEL 表达式（rules/computed）走受限环境，本身不含这些片段。
 */
public final class ScriptGuard {

    private static final Pattern SCRIPT = Pattern.compile(
        "(?is)(<\\s*script|<\\s*/\\s*script|javascript\\s*:|\\bon[a-z]{3,}\\s*=|\\bfunction\\s*\\*?\\s*[A-Za-z_$]*\\s*\\(|=>|\\beval\\s*\\("
            + "|new\\s+Function\\b|\\bimport\\s*\\(|\\brequire\\s*\\(|document\\.|window\\.|<\\s*iframe|<\\s*img[^>]*onerror|\\$\\{)");
    private static final Pattern SCRIPT_KEY = Pattern.compile("(?i)^(script|js|javascript|code|handler|render|template|on[A-Z].*|on[a-z]+|function|func|inject|html)$");

    private ScriptGuard() {
    }

    public static void scan(JsonNode node, String path, List<ViewIssue> issues) {
        if (node == null) return;
        if (node.isTextual()) {
            if (SCRIPT.matcher(node.textValue()).find()) {
                issues.add(new ViewIssue(path, "FORM_SCRIPT_FORBIDDEN", "不允许脚本或函数代码：" + abbreviate(node.textValue())));
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) scan(node.get(i), path + "/" + i, issues);
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String p = path + "/" + e.getKey();
                // 组件编码字段名 code 合法（action/component 的 code 是编码而非代码）
                if (SCRIPT_KEY.matcher(e.getKey()).matches() && !e.getKey().equals("code")) {
                    issues.add(new ViewIssue(p, "FORM_SCRIPT_FORBIDDEN", "不允许脚本属性：" + e.getKey()));
                }
                scan(e.getValue(), p, issues);
            }
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
