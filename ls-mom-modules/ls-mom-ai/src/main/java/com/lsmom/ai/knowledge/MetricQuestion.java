package com.lsmom.ai.knowledge;

import com.lsmom.action.api.MetricPort;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把"本周一车间甲班异常数"这类问题解析为指标查询：指标（按名称匹配）＋时间范围＋组织。
 * 只做规则解析，不让模型编数字；解析不出指标时交给知识检索。
 */
public final class MetricQuestion {

    public record Org(long id, String name) {
    }

    public record Parsed(MetricPort.MetricInfo metric, String rangeLabel, LocalDate from, LocalDate to, Org org) {
    }

    private static final Pattern RECENT = Pattern.compile("(?:最近|近)(\\d{1,3})天");

    private MetricQuestion() {
    }

    public static Optional<Parsed> parse(String question, List<MetricPort.MetricInfo> metrics, List<Org> orgs, LocalDate today) {
        String q = norm(question);
        MetricPort.MetricInfo metric = metrics.stream()
            .filter(m -> matches(q, norm(m.name())))
            .max(Comparator.comparingInt(m -> m.name().length())).orElse(null);
        if (metric == null) return Optional.empty();
        String label = null;
        LocalDate from = null, to = null;
        if (metric.timed()) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            Matcher m = RECENT.matcher(q);
            if (q.contains("今天") || q.contains("今日")) { label = "今天"; from = today; to = today.plusDays(1); }
            else if (q.contains("昨天") || q.contains("昨日")) { label = "昨天"; from = today.minusDays(1); to = today; }
            else if (q.contains("上周")) { label = "上周"; from = monday.minusWeeks(1); to = monday; }
            else if (q.contains("本周") || q.contains("这周") || q.contains("本星期")) { label = "本周"; from = monday; to = monday.plusWeeks(1); }
            else if (q.contains("上月") || q.contains("上个月")) { label = "上月"; from = today.withDayOfMonth(1).minusMonths(1); to = today.withDayOfMonth(1); }
            else if (q.contains("本月") || q.contains("这个月")) { label = "本月"; from = today.withDayOfMonth(1); to = from.plusMonths(1); }
            else if (q.contains("今年") || q.contains("本年")) { label = "今年"; from = today.withDayOfYear(1); to = from.plusYears(1); }
            else if (m.find()) { int n = Integer.parseInt(m.group(1)); label = "最近 " + n + " 天"; from = today.minusDays(n - 1L); to = today.plusDays(1); }
        }
        Org org = null;
        if (metric.dimensions().contains("orgUnitId")) {
            org = orgs.stream().filter(o -> q.contains(norm(o.name()))).max(Comparator.comparingInt(o -> norm(o.name()).length())).orElse(null);
        }
        return Optional.of(new Parsed(metric, label, from, to, org));
    }

    /** 指标名直接出现，或去掉末尾"数 / 率"后与"多少 / 几 / 数量"等一起出现。 */
    static boolean matches(String q, String name) {
        if (q.contains(name)) return true;
        if (name.endsWith("数") && name.length() > 2) {
            String stem = name.substring(0, name.length() - 1);
            return q.contains(stem) && (q.contains("多少") || q.contains("几") || q.contains("数量") || q.contains("次数"));
        }
        return false;
    }

    static String norm(String s) {
        return s == null ? "" : s.replaceAll("[\\s　]+", "").toLowerCase(Locale.ROOT);
    }

    public static Map<String, Object> filterOrg(List<Long> ids) {
        return Map.of("field", "orgUnitId", "op", "in", "value", ids);
    }
}
