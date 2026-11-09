package com.lsmom.biz.asset;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.OperationRequest;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.meta.concept.ManagedRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 按周期生成点检：每天定时为到期（下次点检日期不晚于今天）的生效计划执行一次 {@code InspectionPlan.generate}，
 * 以系统身份经 Operation 网关执行，留审计。
 */
public class InspectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(InspectionScheduler.class);

    private final JdbcTemplate jdbc;
    private final ManagedRecords records;
    private final OperationGateway gateway;
    private final Clock clock;

    public InspectionScheduler(JdbcTemplate jdbc, ManagedRecords records, OperationGateway gateway, Clock clock) {
        this.jdbc = jdbc;
        this.records = records;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Scheduled(cron = "${lsmom.asset.inspection-cron:0 10 0 * * *}")
    public void daily() {
        int n = generateDue(LocalDate.now(clock));
        if (n > 0) log.info("已按点检计划生成 {} 次点检", n);
    }

    /** 为到期的生效计划各生成一次点检，返回生成次数。单个计划失败只记日志，不影响其他计划。 */
    public int generateDue(LocalDate today) {
        List<Map<String, Object>> due = jdbc.queryForList("select id, version from " + records.table(AssetPack.PLAN)
            + " where status = 'ACTIVE' and del_flag = '0' and " + records.column(AssetPack.PLAN, "nextDue") + " <= ? order by id", today);
        int n = 0;
        ActorContext system = ActorContext.system("点检计划");
        for (Map<String, Object> p : due) {
            try {
                gateway.execute(AssetPack.PLAN + ".generate", OperationRequest.on(String.valueOf(p.get("id")), ((Number) p.get("version")).intValue(),
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()), system);
                n++;
            } catch (RuntimeException e) {
                log.warn("点检计划 {} 生成失败：{}", p.get("id"), e.getMessage());
            }
        }
        return n;
    }
}
