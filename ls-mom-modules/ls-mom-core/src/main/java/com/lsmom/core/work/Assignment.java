package com.lsmom.core.work;

import java.time.LocalDateTime;

/** 工作分派。 */
public record Assignment(Long id, Long workItemId, ResourceType resourceType, String resourceRef, String role,
                         LocalDateTime plannedStart, LocalDateTime plannedEnd, String status) {

    public enum ResourceType { PARTY, TEAM, SITE, EQUIPMENT }
}
