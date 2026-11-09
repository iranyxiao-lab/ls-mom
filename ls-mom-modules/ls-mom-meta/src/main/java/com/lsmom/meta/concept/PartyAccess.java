package com.lsmom.meta.concept;

/**
 * 指定人员可见：该 Concept 的 {@code field}（指向主体的引用字段）等于查看人时，这条记录对其可见，不受数据域限制。
 * 用于派给其他组织人员处理的单据，如不符合项的整改负责人。由业务 Pack 以 Bean 形式声明。
 */
public record PartyAccess(String concept, String field) {
}
