package com.lsmom.biz.production;

import com.lsmom.core.error.LsBizException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

/**
 * 班次时段（字典 ls_shift）：夜班 00:00–08:00、早班 08:00–16:00、中班 16:00–24:00。
 * 一天内的顺序为夜班 → 早班 → 中班，中班之后是次日夜班。
 */
public record ShiftWindow(LocalDate date, String shift) {

    public ShiftWindow {
        if (date == null) throw LsBizException.validation(Map.of("shiftDate", "日期必填"));
        if (!"DAY".equals(shift) && !"EVENING".equals(shift) && !"NIGHT".equals(shift)) {
            throw LsBizException.validation(Map.of("shift", "班次只能是 DAY、EVENING、NIGHT"));
        }
    }

    public static ShiftWindow of(Object date, Object shift) {
        LocalDate d = date instanceof LocalDate ld ? ld : date == null ? null : LocalDate.parse(String.valueOf(date).substring(0, 10));
        return new ShiftWindow(d, shift == null ? null : String.valueOf(shift));
    }

    public LocalDateTime start() {
        return switch (shift) {
            case "NIGHT" -> date.atStartOfDay();
            case "DAY" -> date.atTime(LocalTime.of(8, 0));
            default -> date.atTime(LocalTime.of(16, 0));
        };
    }

    public LocalDateTime end() {
        return start().plusHours(8);
    }

    public ShiftWindow next() {
        return switch (shift) {
            case "NIGHT" -> new ShiftWindow(date, "DAY");
            case "DAY" -> new ShiftWindow(date, "EVENING");
            default -> new ShiftWindow(date.plusDays(1), "NIGHT");
        };
    }

    public String label() {
        return date + " " + switch (shift) {
            case "NIGHT" -> "夜班";
            case "DAY" -> "早班";
            default -> "中班";
        };
    }
}
