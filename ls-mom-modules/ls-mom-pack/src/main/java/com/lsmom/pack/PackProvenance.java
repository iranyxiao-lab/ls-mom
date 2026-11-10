package com.lsmom.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;

import java.util.Locale;

/**
 * Pack 来源追溯：读取已安装制品中的 provenance.json（由 ls-mom-cli 生成 / 转换时写入）。
 * 返回来源类型、来源应用与版本、许可证、转换器与模型版本、人工修改历史、需人工处理项；
 * 指定 Concept 时附带该 Concept 及其字段、子表、状态机迁移的来源，并筛出与之相关的人工修改和需人工项。
 */
public final class PackProvenance {

    private PackProvenance() {
    }

    public static ObjectNode of(PackStore store, String pack, String concept) {
        PackStore.Pack p = store.get(pack);
        ObjectNode out = PackJson.JSON.createObjectNode();
        out.put("pack", p.code());
        out.put("version", p.version());
        out.put("artifactSha", p.artifactSha());
        PackSource src = p.artifactSha() == null ? null : store.artifact(p.artifactSha());
        byte[] raw = src == null ? null : src.file(PackContents.PROVENANCE);
        if (raw == null) {
            out.putNull("provenance");
            if (concept != null) throw new LsBizException("PACK_NO_PROVENANCE", "Pack " + pack + " 不是由生成或转换得到，没有来源记录");
            return out;
        }
        JsonNode prov = PackJson.read(PackContents.PROVENANCE, raw);
        for (String k : new String[]{"sourceType", "source", "license", "converter", "model", "generatedAt", "metrics"}) {
            if (prov.has(k)) out.set(k, prov.get(k));
        }
        if (concept == null) {
            out.set("manualEdits", prov.path("manualEdits"));
            out.set("manualRequired", prov.path("manualRequired"));
            out.set("elements", prov.path("elements"));
            return out;
        }
        JsonNode el = prov.path("elements").path(concept);
        if (el.isMissingNode()) throw LsBizException.notFound("Concept 来源", concept);
        ObjectNode c = out.putObject("concept");
        c.put("code", concept);
        c.setAll((ObjectNode) el);
        ArrayNode ops = c.putArray("operations");
        prov.path("elements").fields().forEachRemaining(e -> {
            if (concept.equals(e.getValue().path("concept").asText())) ops.add(PackJson.JSON.createObjectNode().put("code", e.getKey()).setAll((ObjectNode) e.getValue()));
        });
        String simple = concept.substring(concept.lastIndexOf('.') + 1);
        String file = kebab(simple);
        out.set("manualEdits", filter(prov.path("manualEdits"), concept, file));
        out.set("manualRequired", filter(prov.path("manualRequired"), concept, file));
        String sourceName = el.path("source").path("name").asText(null);
        if (sourceName != null) {
            ArrayNode mr = (ArrayNode) out.get("manualRequired");
            String srcPath = el.path("source").path("path").asText("");
            for (JsonNode m : prov.path("manualRequired")) {
                if (!srcPath.isEmpty() && m.path("path").asText().startsWith(srcPath) && !mr.toString().contains(m.toString())) mr.add(m);
            }
        }
        return out;
    }

    private static ArrayNode filter(JsonNode items, String concept, String file) {
        ArrayNode out = PackJson.JSON.createArrayNode();
        for (JsonNode i : items) {
            String path = i.path("path").asText();
            if (path.contains(concept) || path.toLowerCase(Locale.ROOT).contains(file)) out.add(i);
        }
        return out;
    }

    static String kebab(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

}
