package com.lsmom.biz.consultation;

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

/** 到截止时间仍在征求中的议题由系统截止。 */
public class ConsultationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConsultationScheduler.class);

    private final JdbcTemplate jdbc;
    private final ManagedRecords records;
    private final OperationGateway gateway;
    private final Clock clock;

    public ConsultationScheduler(JdbcTemplate jdbc, ManagedRecords records, OperationGateway gateway, Clock clock) {
        this.jdbc = jdbc;
        this.records = records;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${lsmom.consultation.close-ms:60000}", initialDelayString = "${lsmom.consultation.close-ms:60000}")
    public void tick() {
        int n = closeDue(LocalDateTime.now(clock));
        if (n > 0) log.info("到期截止议题 {} 个", n);
    }

    public int closeDue(LocalDateTime now) {
        List<Map<String, Object>> due = jdbc.queryForList("select id, version from " + records.table(ConsultationPack.TOPIC)
            + " where status = 'OPEN' and del_flag = '0' and " + records.column(ConsultationPack.TOPIC, "deadline") + " <= ? order by id", now);
        ActorContext system = ActorContext.system("议题到期截止");
        int n = 0;
        for (Map<String, Object> t : due) {
            try {
                gateway.execute(ConsultationPack.TOPIC + ".close", OperationRequest.on(String.valueOf(t.get("id")), ((Number) t.get("version")).intValue(),
                    JsonNodeFactory.instance.objectNode()), system);
                n++;
            } catch (RuntimeException e) {
                log.warn("议题 {} 到期截止失败：{}", t.get("id"), e.getMessage());
            }
        }
        return n;
    }
}
