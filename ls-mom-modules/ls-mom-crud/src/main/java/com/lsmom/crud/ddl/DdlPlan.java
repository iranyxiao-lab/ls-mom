package com.lsmom.crud.ddl;

import java.util.List;

/**
 * 一次 Concept 结构变更的 DDL 计划。breaking=true 的计划（改类型、删列、改名）必须经变更集评审后才能执行。
 */
public record DdlPlan(String concept, String description, List<String> statements, boolean breaking, List<String> breakingReasons) {

    public boolean isEmpty() {
        return statements.isEmpty();
    }

    public String script() {
        return String.join(";\n", statements) + (statements.isEmpty() ? "" : ";\n");
    }
}
