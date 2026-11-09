package com.lsmom.core.observation;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 事实观察。 */
public record Observation(Long id, String objectType, String objectId, String itemCode, BigDecimal valueNum, String valueText,
                          String unit, LocalDateTime observedAt, Source source, String quality, Long orgUnitId, Long recordedBy) {

    public enum Source { MANUAL, DCS, EXTERNAL }
}
