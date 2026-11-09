package com.lsmom.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 语义化版本（主.次.修订）与版本范围：{@code >=1.0.0 <2.0.0}、{@code ^1.2.0}、{@code ~1.2.0}、{@code 1.x}、{@code 1.2.3}。 */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    private static final Pattern VERSION = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");
    private static final Pattern COMPARATOR = Pattern.compile("^(>=|<=|>|<|=|\\^|~)?(\\d+)(?:\\.(\\d+|x|\\*))?(?:\\.(\\d+|x|\\*))?$");

    public static SemVer parse(String s) {
        Matcher m = VERSION.matcher(s == null ? "" : s.trim());
        if (!m.matches()) throw new IllegalArgumentException("版本号不合法：" + s + "（应为 主.次.修订）");
        return new SemVer(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    public SemVer nextPatch() {
        return new SemVer(major, minor, patch + 1);
    }

    @Override
    public int compareTo(SemVer o) {
        if (major != o.major) return Integer.compare(major, o.major);
        if (minor != o.minor) return Integer.compare(minor, o.minor);
        return Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    /** 解析版本范围；空格分隔的多个条件需同时满足。不合法时抛出 IllegalArgumentException。 */
    public static Predicate<SemVer> range(String range) {
        if (range == null || range.isBlank()) throw new IllegalArgumentException("版本范围不能为空");
        List<Predicate<SemVer>> all = new ArrayList<>();
        for (String part : range.trim().split("\\s+")) all.add(comparator(part));
        return v -> all.stream().allMatch(p -> p.test(v));
    }

    private static Predicate<SemVer> comparator(String part) {
        Matcher m = COMPARATOR.matcher(part);
        if (!m.matches()) throw new IllegalArgumentException("版本范围不合法：" + part);
        String op = m.group(1) == null ? "=" : m.group(1);
        int major = Integer.parseInt(m.group(2));
        boolean minorWild = m.group(3) == null || m.group(3).equals("x") || m.group(3).equals("*");
        boolean patchWild = minorWild || m.group(4) == null || m.group(4).equals("x") || m.group(4).equals("*");
        int minor = minorWild ? 0 : Integer.parseInt(m.group(3));
        int patch = patchWild ? 0 : Integer.parseInt(m.group(4));
        SemVer base = new SemVer(major, minor, patch);
        return switch (op) {
            case ">=" -> v -> v.compareTo(base) >= 0;
            case ">" -> v -> v.compareTo(base) > 0;
            case "<=" -> v -> v.compareTo(base) <= 0;
            case "<" -> v -> v.compareTo(base) < 0;
            case "^" -> v -> v.compareTo(base) >= 0 && (major > 0 ? v.major == major : minorWild || v.minor == minor && v.major == 0);
            case "~" -> v -> v.compareTo(base) >= 0 && v.major == major && (minorWild || v.minor == minor);
            default -> v -> v.major == major && (minorWild || v.minor == minor) && (patchWild || v.patch == patch);
        };
    }
}
