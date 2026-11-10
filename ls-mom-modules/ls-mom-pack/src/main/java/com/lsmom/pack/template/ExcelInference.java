package com.lsmom.pack.template;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lsmom.core.error.LsBizException;
import com.lsmom.pack.PackJson;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 由 Excel 表头与前 50 行推断台账字段，输出 LS-IR 片段（与模板库相同，可在向导里逐项修改后生成 Pack 草稿）。
 * 每列推断：日期 / 日期时间、整数 / 小数、二值字典（是/否、正常/异常、合格/不合格…）、少量取值的选项字典、
 * 设备 / 人员 / 组织引用（取值大多能在现有数据中找到）、长文本；没有空值的列推断为必填。
 * 每个字段附 {@code evidence} 说明推断依据。
 */
public final class ExcelInference {

    /** 引用匹配：取值中能在该对象里找到的个数，以及一个匹配到的记录 ID（作黄金场景样例） */
    public interface RefMatcher {
        Match match(String concept, Set<String> values);
    }

    public record Match(int hits, Long sampleId) {
    }

    public static final int MAX_ROWS = 50;
    private static final List<String> REF_CONCEPTS = List.of("ls.asset.Asset", "ls.core.Party", "ls.core.OrgUnit");
    private static final Map<String, String> REF_LABEL = Map.of("ls.asset.Asset", "设备", "ls.core.Party", "人员", "ls.core.OrgUnit", "组织");
    private static final Pattern DATE = Pattern.compile("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}$");
    private static final Pattern DATETIME = Pattern.compile("^\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}[ T]\\d{1,2}:\\d{2}(:\\d{2}(\\.\\d+)?)?$");
    private static final Pattern INT = Pattern.compile("^-?\\d{1,15}$");
    private static final Pattern DEC = Pattern.compile("^-?\\d{1,15}\\.\\d+$");
    /** 常见二值字典：取值 → 编码 */
    private static final List<Map<String, String>> BINARY = List.of(
        Map.of("是", "YES", "否", "NO"), Map.of("正常", "OK", "异常", "ABNORMAL"), Map.of("合格", "PASS", "不合格", "FAIL"),
        Map.of("有", "YES", "无", "NO"), Map.of("Y", "YES", "N", "NO"), Map.of("OK", "OK", "NG", "NG"), Map.of("√", "YES", "×", "NO"),
        Map.of("通过", "PASS", "不通过", "FAIL"), Map.of("完成", "DONE", "未完成", "UNDONE"));
    /** 常见表头 → 字段编码 */
    private static final Map<String, String> CODES = new LinkedHashMap<>();

    static {
        String[][] m = {
            {"点检日期", "checkDate"}, {"检查日期", "checkDate"}, {"日期", "date"}, {"时间", "time"}, {"设备编号", "asset"}, {"设备编码", "asset"},
            {"设备名称", "asset"}, {"设备", "asset"}, {"点检人", "checker"}, {"检查人", "checker"}, {"负责人", "owner"}, {"责任人", "owner"},
            {"操作人", "operator"}, {"登记人", "registrant"}, {"申请人", "applicant"}, {"班组", "team"}, {"部门", "dept"}, {"车间", "workshop"},
            {"结果", "result"}, {"状态", "state"}, {"是否正常", "normal"}, {"是否合格", "qualified"}, {"温度", "temperature"}, {"压力", "pressure"},
            {"电流", "current"}, {"电压", "voltage"}, {"转速", "speed"}, {"数量", "qty"}, {"金额", "amount"}, {"单价", "price"}, {"备注", "remark"},
            {"说明", "note"}, {"描述", "description"}, {"问题", "problem"}, {"措施", "measures"}, {"名称", "name"}, {"编号", "no"}, {"单号", "no"},
            {"区域", "area"}, {"位置", "location"}, {"规格", "spec"}, {"型号", "model"}, {"类型", "type"}, {"等级", "level"}, {"照片", "photo"},
            {"附件", "attachment"}, {"期限", "deadline"}, {"截止日期", "deadline"}};
        for (String[] e : m) CODES.put(e[0], e[1]);
    }

    private static final Set<String> RESERVED = Set.of("id", "status", "version", "orgId", "createdBy", "createdAt", "updatedBy", "updatedAt",
        "schemaVersion", "deleted");

    private final RefMatcher refs;

    public ExcelInference(RefMatcher refs) {
        this.refs = refs;
    }

    /**
     * @param sheet   工作表名（作显示名）
     * @param headers 表头
     * @param rows    数据行（取前 50 行；单元格已转成文本，日期为 yyyy-MM-dd）
     */
    public ObjectNode infer(String fileName, String sheet, List<String> headers, List<List<String>> rows) {
        if (headers == null || headers.stream().allMatch(h -> h == null || h.isBlank())) {
            throw LsBizException.validation(Map.of("headers", "没有识别到表头：请把字段名放在第一行"));
        }
        List<List<String>> sample = rows == null ? List.of() : rows.stream().limit(MAX_ROWS)
            .filter(r -> r != null && r.stream().anyMatch(c -> c != null && !c.isBlank())).toList();
        ObjectNode frag = PackJson.JSON.createObjectNode();
        String name = sheet == null || sheet.isBlank() || sheet.matches("(?i)sheet\\d*") ? "台账" : sheet.trim();
        frag.put("name", name);
        frag.put("entity", "Record");
        frag.put("entityName", name);
        frag.put("description", "由 Excel「" + (fileName == null ? "" : fileName) + "」生成");
        ArrayNode fields = frag.putArray("fields");
        ObjectNode samples = frag.putObject("samples");
        Set<String> used = new LinkedHashSet<>();
        for (int c = 0; c < headers.size(); c++) {
            String header = headers.get(c) == null ? "" : headers.get(c).trim();
            if (header.isEmpty()) continue;
            List<String> values = new ArrayList<>();
            int blanks = 0;
            for (List<String> r : sample) {
                String v = c < r.size() && r.get(c) != null ? r.get(c).trim() : "";
                if (v.isEmpty()) blanks++;
                else values.add(v);
            }
            ObjectNode f = fields.addObject();
            f.put("code", code(header, c, used));
            f.put("displayName", header);
            f.put("column", c);
            List<String> why = new ArrayList<>();
            describe(f, header, values, why);
            boolean required = !sample.isEmpty() && blanks == 0;
            if (required) {
                f.put("required", true);
                why.add("前 " + sample.size() + " 行都有值，设为必填");
            }
            ObjectNode props = f.with("props");
            if (fields.size() <= 5 && !"TEXT".equals(f.path("dataType").asText()) && !"FILE".equals(f.path("dataType").asText())) {
                props.put("queryable", true);
            }
            if (Set.of("DATE", "DATETIME", "INTEGER", "DECIMAL").contains(f.path("dataType").asText())) props.put("sortable", true);
            if (props.isEmpty()) f.remove("props");
            f.put("evidence", String.join("；", why));
            if ("REF".equals(f.path("dataType").asText())) {
                if (f.hasNonNull("sampleId")) samples.put(f.path("code").asText(), f.remove("sampleId").asLong());
            } else if (!values.isEmpty()) {
                putSample(samples, f, values.get(0));
            }
        }
        ObjectNode flow = frag.putObject("flow");
        flow.put("name", name + "审核");
        flow.putArray("nodes").addObject().put("key", "audit").put("name", "审核").put("type", "AUDIT").put("role", "workshop_leader");
        ObjectNode prov = frag.putObject("provenance");
        prov.put("source", "EXCEL");
        prov.put("file", fileName == null ? "" : fileName);
        prov.put("sheet", sheet == null ? "" : sheet);
        prov.put("sampleRows", sample.size());
        return frag;
    }

    private void describe(ObjectNode f, String header, List<String> values, List<String> why) {
        if (values.isEmpty()) {
            f.put("dataType", "STRING");
            why.add("样例为空，默认文本");
            return;
        }
        Set<String> distinct = new LinkedHashSet<>(values);
        if (values.stream().allMatch(v -> DATETIME.matcher(v).matches() || DATE.matcher(v).matches()) && values.stream().anyMatch(v -> DATETIME.matcher(v).matches())) {
            f.put("dataType", "DATETIME");
            why.add("全部是日期时间");
            return;
        }
        if (values.stream().allMatch(v -> DATE.matcher(v).matches() && date(v) != null)) {
            f.put("dataType", "DATE");
            why.add("全部是日期");
            return;
        }
        Map<String, String> binary = binary(distinct);
        if (binary != null) {
            f.put("dataType", "ENUM");
            ArrayNode opts = f.putArray("options");
            binary.forEach((label, code) -> opts.addObject().put("value", code).put("label", label));
            why.add("只有两种取值（" + String.join("/", binary.keySet()) + "），推断为二值字典");
            return;
        }
        if (values.stream().allMatch(v -> INT.matcher(v).matches())) {
            f.put("dataType", "INTEGER");
            why.add("全部是整数");
            return;
        }
        if (values.stream().allMatch(v -> INT.matcher(v).matches() || DEC.matcher(v).matches())) {
            f.put("dataType", "DECIMAL");
            why.add("全部是数字（含小数）");
            return;
        }
        for (String concept : REF_CONCEPTS) {
            Match m = refs.match(concept, distinct);
            int hit = m.hits();
            if (hit > 0 && hit * 10 >= distinct.size() * 8) {
                f.put("dataType", "REF");
                f.put("refConcept", concept);
                if (m.sampleId() != null) f.put("sampleId", m.sampleId());
                why.add(distinct.size() + " 个取值中 " + hit + " 个能在现有" + REF_LABEL.get(concept) + "中找到，推断为" + REF_LABEL.get(concept) + "引用");
                return;
            }
        }
        int maxLen = values.stream().mapToInt(String::length).max().orElse(0);
        if (distinct.size() <= 6 && values.size() >= 4 && distinct.size() * 2 <= values.size() && maxLen <= 20) {
            f.put("dataType", "ENUM");
            ArrayNode opts = f.putArray("options");
            int i = 1;
            for (String v : distinct) opts.addObject().put("value", "V" + i++).put("label", v);
            why.add("取值重复且只有 " + distinct.size() + " 种，推断为选项字典");
            return;
        }
        if (maxLen > 200) {
            f.put("dataType", "TEXT");
            why.add("最长 " + maxLen + " 字，推断为长文本");
            return;
        }
        f.put("dataType", "STRING");
        f.with("props").put("maxLength", Math.min(500, Math.max(50, ((maxLen * 2 + 49) / 50) * 50)));
        why.add("文本，最长 " + maxLen + " 字");
    }

    private static Map<String, String> binary(Set<String> distinct) {
        if (distinct.size() > 2) return null;
        for (Map<String, String> pair : BINARY) {
            Map<String, String> upper = new LinkedHashMap<>();
            pair.forEach((k, v) -> upper.put(k.toUpperCase(Locale.ROOT), v));
            if (distinct.stream().allMatch(d -> upper.containsKey(d.toUpperCase(Locale.ROOT)))) {
                // 两个取值按字典固定顺序输出（只出现一种时也带上另一种）
                Map<String, String> out = new LinkedHashMap<>();
                pair.entrySet().stream().sorted(Map.Entry.comparingByValue(ExcelInference::positiveFirst)).forEach(e -> out.put(e.getKey(), e.getValue()));
                return out;
            }
        }
        return null;
    }

    private static int positiveFirst(String a, String b) {
        Set<String> pos = Set.of("YES", "OK", "PASS", "DONE");
        return Boolean.compare(!pos.contains(a), !pos.contains(b));
    }

    private static void putSample(ObjectNode samples, ObjectNode f, String v) {
        String code = f.path("code").asText();
        switch (f.path("dataType").asText()) {
            case "DATE" -> samples.put(code, date(v).toString());
            case "DATETIME" -> samples.put(code, v.replace('/', '-').replace('.', '-').replace(' ', 'T'));
            case "INTEGER" -> samples.put(code, Long.parseLong(v));
            case "DECIMAL" -> samples.put(code, new BigDecimal(v));
            case "ENUM" -> {
                for (var o : f.path("options")) {
                    if (o.path("label").asText().equalsIgnoreCase(v)) samples.put(code, o.path("value").asText());
                }
            }
            default -> samples.put(code, v.length() > 50 ? v.substring(0, 50) : v);
        }
    }

    private static LocalDate date(String v) {
        try {
            String[] p = v.split("[-/.]");
            return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 表头 → 字段编码：常见词对照，其余用 f + 列序号；重复时加序号 */
    static String code(String header, int column, Set<String> used) {
        String h = header.replaceAll("[\\s（(].*$", "").trim();
        String base = CODES.get(h);
        if (base == null) {
            for (var e : CODES.entrySet()) {
                if (h.endsWith(e.getKey())) {
                    base = e.getValue();
                    break;
                }
            }
        }
        if (base == null && h.matches("[A-Za-z][A-Za-z0-9 _-]*")) {
            String[] parts = h.trim().split("[ _-]+");
            StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
            for (int i = 1; i < parts.length; i++) sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1).toLowerCase(Locale.ROOT));
            base = sb.toString();
        }
        if (base == null || RESERVED.contains(base)) base = "f" + (column + 1);
        String c = base;
        for (int i = 2; used.contains(c); i++) c = base + i;
        used.add(c);
        return c;
    }
}
