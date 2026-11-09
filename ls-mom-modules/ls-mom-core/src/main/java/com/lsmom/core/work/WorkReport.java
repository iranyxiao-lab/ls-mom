package com.lsmom.core.work;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 工作填报。 */
public record WorkReport(Long id, Long workItemId, Long reporterParty, String status, BigDecimal progress,
                         Integer laborMinutes, BigDecimal outputQty, String outputUnit, List<Consumption> consumption,
                         String remark, LocalDateTime submittedAt, int version) {

    /** 物料消耗事实（不扣减库存）。 */
    public record Consumption(String name, BigDecimal qty, String unit) {
    }
}
