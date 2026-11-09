package com.lsmom.action.api;

/** 写入风险分级：C0 低风险台账可走通用 CRUD；C1 必须经 Operation＋状态机＋守卫；C2 还需专用处理器＋人工审批＋完整审计。 */
public enum Risk { C0, C1, C2 }
