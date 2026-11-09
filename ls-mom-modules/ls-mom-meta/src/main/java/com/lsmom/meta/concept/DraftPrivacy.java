package com.lsmom.meta.concept;

/**
 * 草稿私有：该 Concept 处于 {@code status} 状态的记录只有创建人可见（列表、详情、引用、统计都不返回），
 * 不受数据域放宽影响。用于"确认前别人看不到"的单据，如交接班摘要草稿。由业务 Pack 以 Bean 形式声明。
 */
public record DraftPrivacy(String concept, String status) {
}
