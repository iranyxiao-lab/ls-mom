package com.lsmom.core.org;

/** 负责人解析口径。 */
public enum LeaderKind {
    /** 本组织（部门/班组）负责人 */
    SELF,
    /** 所在部门负责人：最近的 DEPARTMENT 类型组织 */
    DEPARTMENT,
    /** 所在车间负责人：最近的 WORKSHOP 类型组织 */
    WORKSHOP,
    /** 上级负责人：上级组织负责人 */
    SUPERIOR
}
