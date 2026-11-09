package com.lsmom.core.org;

/** 业务数据域口径。 */
public enum DataScopeLevel {
    /** 仅本人 */
    SELF,
    /** 本班组（主组织及其下级） */
    TEAM,
    /** 本车间（主组织所在车间及其下级） */
    WORKSHOP,
    /** 本部门及下级（主组织及下级） */
    DEPT_AND_CHILD,
    /** 全部 */
    ALL
}
