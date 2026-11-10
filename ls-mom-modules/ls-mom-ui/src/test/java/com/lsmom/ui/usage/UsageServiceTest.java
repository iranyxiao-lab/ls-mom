package com.lsmom.ui.usage;

import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UsageServiceTest {

    private final JdbcTemplate jdbc = PgTestSupport.jdbc();
    private final UsageService usage = new UsageService(jdbc);

    private static String task() {
        return PgTestSupport.uniq("t").toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    @AfterEach
    void enableAgain() {
        usage.setEnabled(true, null);
    }

    @Test
    void 只存名称与耗时_自由文本和多余内容不入库() {
        String t = task();
        int n = usage.record(List.of(
            new UsageService.Event(t, "asset", "abandon", 1200),
            new UsageService.Event(t, "张三 13800000000", "step", 10),   // 步骤不是标识符 → 置空
            new UsageService.Event("泵站 P-101 漏油", "title", "start", 5), // 任务不是标识符 → 丢弃
            new UsageService.Event(t, null, "submit-value", 5),           // 未知事件类型 → 丢弃
            new UsageService.Event(t, null, "complete", -3)));            // 负耗时 → 置空
        assertThat(n).isEqualTo(3);
        List<Map<String, Object>> rows = jdbc.queryForList("select * from ui_usage_event where task = ? order by id", t);
        assertThat(rows).extracting(r -> r.get("step")).containsExactly("asset", null, null);
        assertThat(rows).extracting(r -> r.get("duration_ms")).containsExactly(1200, 10, null);
        // 表结构本身没有用户、内容类字段
        List<String> cols = jdbc.queryForList("select column_name from information_schema.columns where table_name = 'ui_usage_event'", String.class);
        assertThat(cols).containsExactlyInAnyOrder("id", "occurred_at", "task", "step", "kind", "duration_ms");
    }

    @Test
    void 关闭后拒收() {
        String t = task();
        usage.setEnabled(false, 1L);
        assertThat(usage.enabled()).isFalse();
        assertThat(usage.record(List.of(new UsageService.Event(t, null, "start", null)))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ui_usage_event where task = ?", Long.class, t)).isZero();
        usage.setEnabled(true, 1L);
        assertThat(usage.record(List.of(new UsageService.Event(t, null, "start", null)))).isEqualTo(1);
    }

    @Test
    void 单批最多五十条() {
        String t = task();
        List<UsageService.Event> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) many.add(new UsageService.Event(t, null, "step", i));
        assertThat(usage.record(many)).isEqualTo(UsageService.MAX_BATCH);
    }

    @Test
    void 放弃率达到三成的步骤标为高放弃率() {
        String t = task();
        List<UsageService.Event> ev = new ArrayList<>();
        for (int i = 0; i < 10; i++) ev.add(new UsageService.Event(t, null, "start", null));
        for (int i = 0; i < 4; i++) ev.add(new UsageService.Event(t, "asset", "abandon", 30_000));
        ev.add(new UsageService.Event(t, "title", "abandon", 2_000));
        for (int i = 0; i < 5; i++) ev.add(new UsageService.Event(t, null, "complete", 40_000 + i * 1000));
        ev.add(new UsageService.Event(t, "save", "error", null));
        usage.record(ev);

        Map<String, Object> r = usage.report(30).stream().filter(x -> t.equals(x.get("task"))).findFirst().orElseThrow();
        assertThat(r).containsEntry("started", 10L).containsEntry("completed", 5L).containsEntry("abandoned", 5L).containsEntry("errors", 1L)
            .containsEntry("medianMs", 42_000L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) r.get("steps");
        assertThat(steps.get(0)).containsEntry("step", "asset").containsEntry("rate", 0.4).containsEntry("high", true);
        assertThat(steps.get(1)).containsEntry("step", "title").containsEntry("high", false);
    }

    @Test
    void 样本不足不标记() {
        String t = task();
        usage.record(List.of(new UsageService.Event(t, null, "start", null), new UsageService.Event(t, "asset", "abandon", null)));
        Map<String, Object> r = usage.report(30).stream().filter(x -> t.equals(x.get("task"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) r.get("steps");
        assertThat(steps.get(0)).containsEntry("rate", 1.0).containsEntry("high", false);
    }
}
