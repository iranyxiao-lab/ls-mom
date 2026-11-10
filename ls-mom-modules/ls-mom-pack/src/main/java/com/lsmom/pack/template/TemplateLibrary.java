package com.lsmom.pack.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.pack.PackJson;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 台账模板库：classpath 下 lsmom-templates/*.yaml，每个文件是一个 LS-IR 片段（见 {@link LedgerGenerator}）。 */
public final class TemplateLibrary {

    private static volatile List<JsonNode> cache;

    private TemplateLibrary() {
    }

    public static List<JsonNode> all() {
        List<JsonNode> c = cache;
        if (c != null) return c;
        List<JsonNode> out = new ArrayList<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:lsmom-templates/*.yaml")) {
                try (InputStream in = r.getInputStream()) {
                    out.add(PackJson.YAML.readTree(in));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取模板库失败", e);
        }
        out.sort(Comparator.comparing(n -> n.path("code").asText()));
        cache = List.copyOf(out);
        return cache;
    }

    public static JsonNode get(String code) {
        return all().stream().filter(n -> n.path("code").asText().equals(code)).findFirst()
            .orElseThrow(() -> LsBizException.notFound("模板", code));
    }
}
