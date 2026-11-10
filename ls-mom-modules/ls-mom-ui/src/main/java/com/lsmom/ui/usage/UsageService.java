package com.lsmom.ui.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 匿名使用埋点：只接受任务名、步骤名（标识符格式）、事件类型和耗时，其他内容一概不存。
 * 管理员关闭后服务端拒收，前端也停止上报。
 */
@Service
public class UsageService {

    static final String ENABLED_KEY = "usage.enabled";
    /** 某步骤放弃数占开始数的比例达到该值即标为高放弃率。 */
    static final double HIGH_ABANDON_RATE = 0.3;
    /** 开始次数少于该值时样本不足，不做标记。 */
    static final int MIN_SAMPLE = 5;
    static final int MAX_BATCH = 50;
    static final int RETENTION_DAYS = 180;

    /** 名称只允许小写标识符：自由文本（可能含表单值或个人信息）在格式上就进不来。 */
    static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    static final Set<String> KINDS = Set.of("start", "step", "complete", "abandon", "error");

    public record Event(String task, String step, String kind, Integer durationMs) {
    }

    private final JdbcTemplate jdbc;

    public UsageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean enabled() {
        List<String> v = jdbc.queryForList("select setting_value from ui_setting where setting_key = ?", String.class, ENABLED_KEY);
        return v.isEmpty() || !"false".equals(v.get(0));
    }

    public void setEnabled(boolean on, Long userId) {
        jdbc.update("""
            insert into ui_setting (setting_key, setting_value, updated_by, updated_at) values (?, ?, ?, now())
            on conflict (setting_key) do update set setting_value = excluded.setting_value, updated_by = excluded.updated_by, updated_at = now()
            """, ENABLED_KEY, String.valueOf(on), userId);
    }

    /** 记录一批事件，返回实际入库条数（关闭时为 0；不合格的条目丢弃）。 */
    public int record(List<Event> events) {
        if (events == null || events.isEmpty() || !enabled()) return 0;
        List<Object[]> rows = new ArrayList<>();
        for (Event e : events.subList(0, Math.min(events.size(), MAX_BATCH))) {
            if (e == null || e.task() == null || !NAME.matcher(e.task()).matches() || !KINDS.contains(e.kind())) continue;
            String step = e.step() != null && NAME.matcher(e.step()).matches() ? e.step() : null;
            Integer d = e.durationMs() == null || e.durationMs() < 0 ? null : Math.min(e.durationMs(), 86_400_000);
            rows.add(new Object[]{e.task(), step, e.kind(), d});
        }
        if (rows.isEmpty()) return 0;
        jdbc.batchUpdate("insert into ui_usage_event (task, step, kind, duration_ms) values (?, ?, ?, ?)", rows);
        return rows.size();
    }

    /** 放弃点报表：每个任务的开始／完成／放弃／错误次数、完成耗时中位数，以及各步骤放弃率（高放弃率标记）。 */
    public List<Map<String, Object>> report(int days) {
        int window = Math.max(1, Math.min(days, RETENTION_DAYS));
        List<Map<String, Object>> tasks = jdbc.queryForList("""
            select task,
                   count(*) filter (where kind = 'start')    as started,
                   count(*) filter (where kind = 'complete') as completed,
                   count(*) filter (where kind = 'abandon')  as abandoned,
                   count(*) filter (where kind = 'error')    as errors,
                   round(percentile_cont(0.5) within group (order by duration_ms) filter (where kind = 'complete')) as median_ms
            from ui_usage_event
            where occurred_at > now() - make_interval(days => ?)
            group by task
            order by count(*) filter (where kind = 'abandon') desc, task
            """, window);
        List<Map<String, Object>> steps = jdbc.queryForList("""
            select task, coalesce(step, '') as step, count(*) as abandoned
            from ui_usage_event
            where kind = 'abandon' and occurred_at > now() - make_interval(days => ?)
            group by task, step
            order by task, count(*) desc
            """, window);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tasks) {
            long started = ((Number) t.get("started")).longValue();
            List<Map<String, Object>> ts = new ArrayList<>();
            for (Map<String, Object> s : steps) {
                if (!t.get("task").equals(s.get("task"))) continue;
                long n = ((Number) s.get("abandoned")).longValue();
                double rate = started == 0 ? 0 : (double) n / started;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("step", s.get("step"));
                row.put("abandoned", n);
                row.put("rate", Math.round(rate * 1000) / 1000.0);
                row.put("high", started >= MIN_SAMPLE && rate >= HIGH_ABANDON_RATE);
                ts.add(row);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", t.get("task"));
            row.put("started", started);
            row.put("completed", ((Number) t.get("completed")).longValue());
            row.put("abandoned", ((Number) t.get("abandoned")).longValue());
            row.put("errors", ((Number) t.get("errors")).longValue());
            row.put("medianMs", t.get("median_ms") == null ? null : ((Number) t.get("median_ms")).longValue());
            row.put("steps", ts);
            out.add(row);
        }
        return out;
    }

    /** 每天清理超过保留期的埋点。 */
    @Scheduled(cron = "0 17 3 * * *")
    public void purge() {
        jdbc.update("delete from ui_usage_event where occurred_at < now() - make_interval(days => ?)", RETENTION_DAYS);
    }
}
