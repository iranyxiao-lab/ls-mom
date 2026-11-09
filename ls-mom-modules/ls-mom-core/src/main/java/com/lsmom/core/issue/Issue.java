package com.lsmom.core.issue;

import java.time.LocalDateTime;

/** Issue 外壳。 */
public record Issue(Long id, String code, String type, String title, String description, String severity, IssueStatus status,
                    Long orgUnitId, String constraintProfile, String sourceType, String sourceKey, int occurrenceCount,
                    LocalDateTime lastOccurredAt, Long reporterParty, Long ownerParty, LocalDateTime dueAt,
                    Long verifiedBy, String verifyConclusion, LocalDateTime verifiedAt, String extConcept, Long extId,
                    LocalDateTime createdAt, int version) {
}
