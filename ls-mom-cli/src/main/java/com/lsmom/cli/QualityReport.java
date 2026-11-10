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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成质量指标：
 * <ul>
 *   <li>{@code record-run <生成目录> --stage compile|install|test --result PASSED|FAILED [--note 说明]}：
 *       把编译、临时库安装、测试的结果追加到该次生成的 report.json（第一次结果即"一次通过"）；</li>
 *   <li>{@code quality <生成目录>... [--out quality.json]}：汇总一次编译与安装成功率、校验通过率、测试一次通过率、
 *       人工修改量与原因、复用比例、需求到 UAT 工时。</li>
 * </ul>
 */
final class QualityReport {

    static final Set<String> STAGES = Set.of("compile", "install", "test");

    private QualityReport() {
    }

    static int recordRun(LsMomCli.Args a, PrintStream out) {
        Path dir = Path.of(a.positional(0, "生成目录"));
        String stage = a.require("stage");
        String result = a.require("result");
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("--stage 只能为 compile、install、test");
        if (!"PASSED".equals(result) && !"FAILED".equals(result)) throw new IllegalArgumentException("--result 只能为 PASSED 或 FAILED");
        recordRun(dir, stage, result, a.option("note", null));
        out.println("已记录 " + stage + " " + result + "：" + dir.resolve("report.json"));
        return 0;
    }

    static void recordRun(Path dir, String stage, String result, String note) {
        Path f = dir.resolve("report.json");
        ObjectNode r = (ObjectNode) read(f);
        ArrayNode runs = r.withArray("runs");
        ObjectNode run = runs.addObject().put("stage", stage).put("result", result).put("at", Instant.now().toString());
        if (note != null) run.put("note", note);
        write(f, r);
    }

    static int cli(LsMomCli.Args a, PrintStream out) {
        List<Path> dirs = a.positional().stream().map(Path::of).toList();
        if (dirs.isEmpty()) throw new IllegalArgumentException("缺少生成目录");
        ObjectNode q = aggregate(dirs);
        String target = a.option("out", null);
        if (target != null) write(Path.of(target), q);
        out.println(LsIr.JSON.valueToTree(q).toPrettyString());
        return 0;
    }

    /** 汇总多次生成的 report.json。 */
    static ObjectNode aggregate(List<Path> dirs) {
        int runs = 0, validated = 0, compileFirst = 0, compileTried = 0, installFirst = 0, installTried = 0, testFirst = 0, testTried = 0;
        int edits = 0, editLines = 0, own = 0, reused = 0, open = 0, blocked = 0;
        double uatHours = 0;
        int uatCount = 0;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        ArrayNode items = LsIr.JSON.createArrayNode();
        for (Path d : dirs) {
            JsonNode r = read(d.resolve("report.json"));
            runs++;
            if ("PASSED".equals(r.path("validation").asText())) validated++;
            Map<String, String> first = new LinkedHashMap<>();
            for (JsonNode x : r.path("runs")) first.putIfAbsent(x.path("stage").asText(), x.path("result").asText());
            if (first.containsKey("compile")) {
                compileTried++;
                if ("PASSED".equals(first.get("compile"))) compileFirst++;
            }
            if (first.containsKey("install")) {
                installTried++;
                if ("PASSED".equals(first.get("install"))) installFirst++;
            }
            if (first.containsKey("test")) {
                testTried++;
                if ("PASSED".equals(first.get("test"))) testFirst++;
            }
            for (JsonNode e : r.path("manualEdits")) {
                edits++;
                editLines += e.path("lines").asInt(0);
                reasons.merge(e.path("reason").asText(), 1, Integer::sum);
            }
            own += r.path("conceptsNew").asInt();
            reused += r.path("conceptsReused").asInt();
            open += r.path("manualRequired").path("open").asInt();
            blocked += r.path("manualRequired").path("blocked").asInt();
            if (r.has("requirementToUatHours")) {
                uatHours += r.path("requirementToUatHours").asDouble();
                uatCount++;
            }
            ObjectNode i = items.addObject();
            i.put("pack", r.path("pack").asText()).put("version", r.path("version").asText()).put("sourceType", r.path("sourceType").asText());
            i.put("validation", r.path("validation").asText());
            first.forEach(i::put);
            i.put("reuseRatio", r.path("reuseRatio").asDouble());
            i.put("manualEdits", r.path("manualEdits").size());
        }
        ObjectNode q = LsIr.JSON.createObjectNode();
        q.put("generatedAt", Instant.now().toString());
        q.put("runs", runs);
        q.put("validationPassRate", rate(validated, runs));
        q.put("compileFirstPassRate", compileTried == 0 ? null : rate(compileFirst, compileTried));
        q.put("installFirstPassRate", installTried == 0 ? null : rate(installFirst, installTried));
        q.put("compileAndInstallFirstPassRate", compileTried == 0 || installTried == 0 ? null
            : rate(Math.min(compileFirst, installFirst), Math.max(compileTried, installTried)));
        q.put("testFirstPassRate", testTried == 0 ? null : rate(testFirst, testTried));
        ObjectNode me = q.putObject("manualEdits").put("count", edits).put("lines", editLines);
        ObjectNode rs = me.putObject("reasons");
        reasons.forEach(rs::put);
        q.put("reuseRatio", own + reused == 0 ? 0 : rate(reused, own + reused));
        q.putObject("manualRequired").put("open", open).put("blocked", blocked);
        q.put("requirementToUatHoursAvg", uatCount == 0 ? null : Math.round(uatHours / uatCount * 10) / 10.0);
        q.set("items", items);
        return q;
    }

    private static double rate(int n, int d) {
        return d == 0 ? 0 : Math.round(n * 1000.0 / d) / 1000.0;
    }

    private static JsonNode read(Path f) {
        try {
            return LsIr.JSON.readTree(Files.readString(f));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path f, JsonNode n) {
        try {
            Files.writeString(f, LsIr.JSON.writeValueAsString(n) + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
