package com.lsmom.biz.training;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.meta.concept.ManagedRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 超时交卷：作答截止时间已到而未交卷的答卷，由系统自动提交已作答部分并判分。 */
public class TrainingScheduler {

    private static final Logger log = LoggerFactory.getLogger(TrainingScheduler.class);

    private final JdbcTemplate jdbc;
    private final ManagedRecords records;
    private final OperationGateway gateway;
    private final Clock clock;

    public TrainingScheduler(JdbcTemplate jdbc, ManagedRecords records, OperationGateway gateway, Clock clock) {
        this.jdbc = jdbc;
        this.records = records;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${lsmom.training.auto-submit-ms:30000}", initialDelayString = "${lsmom.training.auto-submit-ms:30000}")
    public void tick() {
        int n = autoSubmitDue(LocalDateTime.now(clock));
        if (n > 0) log.info("超时自动交卷 {} 份", n);
    }

    public int autoSubmitDue(LocalDateTime now) {
        List<Map<String, Object>> due = jdbc.queryForList("select id, version from " + records.table(TrainingPack.ANSWER)
            + " where status = 'IN_PROGRESS' and del_flag = '0' and " + records.column(TrainingPack.ANSWER, "deadline") + " < ? order by id", now);
        ActorContext system = ActorContext.system("超时自动交卷");
        int n = 0;
        for (Map<String, Object> a : due) {
            try {
                gateway.execute(TrainingPack.ANSWER + ".submit", OperationRequest.on(String.valueOf(a.get("id")), ((Number) a.get("version")).intValue(),
                    JsonNodeFactory.instance.objectNode()), system);
                n++;
            } catch (RuntimeException e) {
                log.warn("答卷 {} 自动交卷失败：{}", a.get("id"), e.getMessage());
            }
        }
        return n;
    }
}
