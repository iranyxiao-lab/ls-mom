package com.lsmom.flow.engine;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 流程定义中允许出现的表达式（生成器只产生这几种）：读取单个流程变量，或变量与字面量比较，以及多实例完成条件。
 * 不允许方法调用、属性访问、Bean 引用或任何其他 EL。引擎侧与部署前扫描共用本白名单。
 */
public final class ExpressionWhitelist {

    private static final String VAR = "[a-zA-Z_][a-zA-Z0-9_]{0,63}";
    private static final String LIT = "('[A-Za-z0-9_.\\-]{0,64}'|-?[0-9]{1,9}|true|false)";
    private static final List<Pattern> ALLOWED = List.of(
        Pattern.compile("^\\$\\{" + VAR + "}$"),
        Pattern.compile("^\\$\\{" + VAR + " (==|!=|>=|<=|>|<) " + LIT + "}$"));

    private ExpressionWhitelist() {
    }

    public static boolean allowed(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (!t.contains("${") && !t.contains("#{")) return true;
        return ALLOWED.stream().anyMatch(p -> p.matcher(t).matches());
    }
}
