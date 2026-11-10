package com.lsmom.dcs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 定时从 Historian 增量读取：先降采样快照，再接入报警。失败只记日志，下轮从游标继续。 */
public class DcsIngestJob {

    private static final Logger LOG = LoggerFactory.getLogger(DcsIngestJob.class);

    private final SnapshotService snapshots;
    private final AlarmService alarms;
    private final HistorianReader reader;

    public DcsIngestJob(SnapshotService snapshots, AlarmService alarms, HistorianReader reader) {
        this.snapshots = snapshots;
        this.alarms = alarms;
        this.reader = reader;
    }

    @Scheduled(fixedDelayString = "${lsmom.dcs.poll-ms:10000}", initialDelayString = "${lsmom.dcs.poll-ms:10000}")
    public void tick() {
        try {
            int n = snapshots.run();
            AlarmService.IngestResult r = alarms.pull(reader);
            if (n > 0 || r.received() > 0) {
                LOG.info("DCS 增量：快照 {} 窗，报警事件 {}（重复 {}，坏值 {}，时钟异常 {}，新报警 {}）", n, r.received(), r.duplicates(),
                    r.badQuality(), r.clockAnomalies(), r.alarmsCreated());
            }
        } catch (RuntimeException e) {
            LOG.warn("DCS 增量读取失败，下轮重试：{}", e.getMessage());
        }
    }
}
