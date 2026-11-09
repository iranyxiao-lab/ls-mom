package com.lsmom.core.document;

/** 附件关联。 */
public record DocumentLink(Long id, Long ossId, String objectType, String objectId, String purpose, Classification classification,
                           int docVersion, String fileName, Long orgUnitId, Long createdBy) {
}
