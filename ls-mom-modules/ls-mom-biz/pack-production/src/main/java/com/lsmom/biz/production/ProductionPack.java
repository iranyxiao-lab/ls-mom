package com.lsmom.biz.production;

/** 轻生产 Pack 的 Concept 编码。 */
public final class ProductionPack {

    public static final String CODE = "ls.production";
    public static final String REQUEST = CODE + ".ProductionRequest";
    public static final String CONTEXT = CODE + ".ProductionContext";
    public static final String SHIFT_PLAN = CODE + ".ShiftPlan";
    public static final String OUTPUT = CODE + ".ProductionOutput";
    public static final String MATERIAL = CODE + ".MaterialRecord";
    public static final String HANDOVER = CODE + ".ShiftHandover";
    public static final String HANDOVER_ITEM = CODE + ".ShiftHandoverItem";

    private ProductionPack() {
    }
}
