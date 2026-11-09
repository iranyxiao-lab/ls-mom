package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 8.3：字段策略合并与前端共用 packs/_shared/field-policy 用例集。 */
class FieldPolicyTest {

    static File cases() {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "packs/_shared/field-policy").isDirectory()) dir = dir.getParentFile();
        if (dir == null) throw new IllegalStateException("找不到 packs/_shared/field-policy");
        return new File(dir, "packs/_shared/field-policy/basic.json");
    }

    @TestFactory
    List<DynamicTest> 共享用例() throws Exception {
        JsonNode suite = new ObjectMapper().readTree(cases());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : suite.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                JsonNode policy = c.path("policy").isNull() ? null : c.path("policy");
                Map<String, FieldPolicy.Access> r = FieldPolicy.resolve(suite.path("form"), policy);
                c.path("expect").fields().forEachRemaining(e -> {
                    FieldPolicy.Access a = r.get(e.getKey());
                    assertThat(a).as(e.getKey()).isNotNull();
                    String got = (a.visible() ? "v" : "-") + " " + (a.editable() ? "e" : "-") + " " + (a.required() ? "r" : "-");
                    assertThat(got).as(c.path("name").asText() + " / " + e.getKey()).isEqualTo(e.getValue().asText());
                });
            }));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(8);
        return tests;
    }
}
