package com.lsmom.core.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务中心：耗时任务（导入、导出、批量操作、Pack 安装）在后台执行，这里记录进度与结果，发起人离开页面后仍能看到并收到完成通知。
 * 进度写入不参与业务事务，任务失败也保留记录。
 */
@Service
public class BackgroundTasks {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Task(long id, String kind, String title, String status, int total, int done, int failed, JsonNode result, String error,
                       LocalDateTime createdAt, LocalDateTime finishedAt, boolean notified) {
    }

    private final JdbcTemplate jdbc;
    /** 进度写入用独立事务：任务在业务事务里运行时，前端也能看到实时进度 */
    private final org.springframework.transaction.support.TransactionTemplate own;

    private final RowMapper<Task> mapper = (rs, i) -> new Task(rs.getLong("id"), rs.getString("kind"), rs.getString("title"),
        rs.getString("status"), rs.getInt("total"), rs.getInt("done"), rs.getInt("failed"), read(rs.getString("result")), rs.getString("error"),
        rs.getObject("created_at", LocalDateTime.class), rs.getObject("finished_at", LocalDateTime.class), rs.getObject("notified_at") != null);

    public BackgroundTasks(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.own = new org.springframework.transaction.support.TransactionTemplate(txm);
        this.own.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 节流的进度回调：至少间隔 500 毫秒写一次，最后一次由 finish 补齐。 */
    public java.util.function.IntConsumer reporter(long id) {
        long[] last = {0};
        return n -> {
            long now = System.currentTimeMillis();
            if (now - last[0] < 500) return;
            last[0] = now;
            progress(id, n, 0);
        };
    }

    public long start(String kind, String title, long userId, int total) {
        Long id = own.execute(t -> jdbc.queryForObject("insert into core_background_task (kind, title, total, created_by) values (?, ?, ?, ?) returning id",
            Long.class, kind, title, total, userId));
        return id == null ? 0 : id;
    }

    public void total(long id, int total) {
        own.executeWithoutResult(t -> jdbc.update("update core_background_task set total = ?, updated_at = now() where id = ?", total, id));
    }

    public void progress(long id, int done, int failed) {
        own.executeWithoutResult(t -> jdbc.update(
            "update core_background_task set done = ?, failed = ?, updated_at = now() where id = ? and status = 'RUNNING'", done, failed, id));
    }

    public void finish(long id, Object result) {
        String json = write(result);
        own.executeWithoutResult(t -> jdbc.update("""
            update core_background_task set status = 'DONE', done = greatest(done, total), result = ?::jsonb, updated_at = now(), finished_at = now()
             where id = ?""", json, id));
    }

    public void fail(long id, String error) {
        String e = error == null ? "任务失败" : error.length() > 1000 ? error.substring(0, 1000) : error;
        own.executeWithoutResult(t -> jdbc.update(
            "update core_background_task set status = 'FAILED', error = ?, updated_at = now(), finished_at = now() where id = ?", e, id));
    }

    /** 本人最近的任务（进行中的在前）。 */
    public List<Task> mine(long userId, int limit) {
        return jdbc.query("""
            select * from core_background_task where created_by = ?
             order by (status = 'RUNNING') desc, created_at desc limit ?""", mapper, userId, Math.min(Math.max(limit, 1), 100));
    }

    public Task get(long id, long userId) {
        return jdbc.query("select * from core_background_task where id = ? and created_by = ?", mapper, id, userId).stream().findFirst()
            .orElseThrow(() -> LsBizException.notFound("任务", id));
    }

    /** 发起人已收到完成通知。 */
    public void notified(long id, long userId) {
        jdbc.update("update core_background_task set notified_at = now() where id = ? and created_by = ? and status <> 'RUNNING'", id, userId);
    }

    private static String write(Object v) {
        try {
            return v == null ? null : JSON.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode read(String s) {
        try {
            return s == null ? null : JSON.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
