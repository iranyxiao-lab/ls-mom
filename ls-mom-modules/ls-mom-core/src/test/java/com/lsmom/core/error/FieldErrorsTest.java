package com.lsmom.core.error;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** modernize-ui-ux 4.4：校验失败的统一字段错误结构。 */
class FieldErrorsTest {

    @Test
    void 主表字段与子表单元格拆分_带编码与修复提示() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("borrower", "借用人必填");
        fields.put("loanLine[2].tool", "第 3 行：工器具必填");
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fields", fields);
        details.put("codes", Map.of("borrower", "REQUIRED", "loanLine[2].tool", "REQUIRED"));
        details.put("fixes", Map.of("loanLine[2].tool", "填写工器具"));
        List<Map<String, Object>> errors = (List<Map<String, Object>>) FieldErrors.complete(details).get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0)).containsEntry("field", "borrower").containsEntry("code", "REQUIRED").doesNotContainKey("rowIndex");
        assertThat(errors.get(1)).containsEntry("key", "loanLine[2].tool").containsEntry("field", "loanLine.tool").containsEntry("table", "loanLine")
            .containsEntry("rowIndex", 2).containsEntry("message", "工器具必填").containsEntry("fix", "填写工器具");
    }

    @Test
    void 没有编码时记为INVALID_已有errors时不改() {
        Map<String, Object> d = Map.of("fields", Map.of("x", "不对"));
        assertThat((List<Map<String, Object>>) FieldErrors.complete(d).get("errors")).singleElement().satisfies(e -> assertThat(e).containsEntry("code", "INVALID"));
        Map<String, Object> done = Map.of("errors", List.of(), "fields", Map.of("x", "y"));
        assertThat(FieldErrors.complete(done)).isSameAs(done);
        assertThat(FieldErrors.complete(null)).isNull();
    }
}
