package com.lsmom.meta.concept;

/**
 * 共享引用：该 Concept 的记录（如设备）可被本组织及上级组织的人在单据中选用，即使其数据范围只有"仅本人"。
 * 只放宽"能否选用为引用"（引用校验、引用搜索与名称回显），不放宽读取台账本身。由业务 Pack 以 Bean 形式声明。
 */
public record SharedReference(String concept) {
}
