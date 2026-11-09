package com.lsmom.core.event;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** 领域事件。 */
public record DomainEvent(UUID eventId, String type, int version, LocalDateTime occurredAt, Long actorParty, Long onBehalfOf,
                          String subjectConcept, String subjectId, Long orgUnitId, String traceId, String idempotencyKey,
                          Map<String, Object> payload) {
}
