package com.lsmom.core.work;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 工作项。 */
public record WorkItem(Long id, String code, WorkType type, String title, String description, Long orgUnitId,
                       String orgUnitName, String constraintProfile, WorkStatus status, int priority,
                       LocalDateTime plannedStart, LocalDateTime plannedEnd, LocalDateTime actualStart, LocalDateTime actualEnd,
                       BigDecimal progress, String blockReason, String formCode, Integer formVersion, Integer flowVersion,
                       Integer schemaVersion, String extConcept, Long extId, String sourceType, Long sourceId,
                       Long createdByParty, LocalDateTime createdAt, int version) {
}
