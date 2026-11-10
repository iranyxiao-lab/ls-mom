package com.lsmom.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 维护 LS-IR 的来源记录：
 * <ul>
 *   <li>{@code record-edit <ir> --by <人> --path <修改位置> --reason <原因> [--lines n]}：追加人工修改记录；</li>
 *   <li>{@code resolve <ir> --path <需人工项路径> --by <人> --resolution <处理结论>}：关闭一项需人工 / 禁止自动转换项。</li>
 * </ul>
 * 修改后重新 generate，Pack 中的 provenance.json 随之更新；未关闭的项会阻止 ChangeSet 发布。
 */
final class ProvenanceEdits {

    private ProvenanceEdits() {
    }

    static int recordEdit(LsMomCli.Args a, PrintStream out) {
        Path file = Path.of(a.positional(0, "LS-IR 文件"));
        ObjectNode ir = (ObjectNode) LsIr.read(file);
        recordEdit(ir, a.require("by"), a.require("path"), a.require("reason"), Integer.parseInt(a.option("lines", "0")));
        write(file, ir);
        out.println("已记录人工修改：" + a.require("path"));
        return 0;
    }

    static int resolve(LsMomCli.Args a, PrintStream out, PrintStream err) {
        Path file = Path.of(a.positional(0, "LS-IR 文件"));
        ObjectNode ir = (ObjectNode) LsIr.read(file);
        int n = resolve(ir, a.require("path"), a.require("by"), a.require("resolution"));
        if (n == 0) {
            err.println("没有找到未处理的需人工项：" + a.require("path"));
            return 1;
        }
        write(file, ir);
        long open = 0;
        for (JsonNode m : ir.path("provenance").path("manualRequired")) if (!"RESOLVED".equals(m.path("status").asText())) open++;
        out.println("已关闭 " + n + " 项，剩余未处理 " + open + " 项");
        return 0;
    }

    static void recordEdit(ObjectNode ir, String by, String path, String reason, int lines) {
        ArrayNode edits = ((ObjectNode) ir.get("provenance")).withArray("manualEdits");
        ObjectNode e = edits.addObject().put("at", Instant.now().toString()).put("by", by).put("path", path).put("reason", reason);
        if (lines > 0) e.put("lines", lines);
    }

    /** 关闭路径相同（或以其开头）的未处理项；返回关闭数量。 */
    static int resolve(ObjectNode ir, String path, String by, String resolution) {
        int n = 0;
        for (JsonNode m : ir.path("provenance").path("manualRequired")) {
            if ("RESOLVED".equals(m.path("status").asText())) continue;
            String p = m.path("path").asText();
            if (p.equals(path) || p.startsWith(path + "#") || p.startsWith(path + "/")) {
                ((ObjectNode) m).put("status", "RESOLVED").put("resolvedBy", by).put("resolution", resolution);
                n++;
            }
        }
        return n;
    }

    private static void write(Path file, JsonNode ir) {
        try {
            Files.writeString(file, LsIr.JSON.writeValueAsString(ir) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
