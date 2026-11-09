package com.lsmom.biz.asset;

/** 设备 Pack 的编码常量。 */
public final class AssetPack {

    public static final String CODE = "ls.asset";
    public static final String ASSET = "ls.asset.Asset";
    public static final String STATUS_LOG = "ls.asset.AssetStatusLog";
    public static final String PLAN = "ls.asset.InspectionPlan";
    public static final String PLAN_ITEM = "ls.asset.InspectionPlanItem";
    public static final String RECORD = "ls.asset.InspectionRecord";
    public static final String RECORD_ITEM = "ls.asset.InspectionRecordItem";
    public static final String DEFECT = "ls.asset.Defect";
    /** Issue 类型与关联对象类型。 */
    public static final String ISSUE_TYPE = "EQUIPMENT_DEFECT";
    public static final String OBJECT_TYPE = "ASSET";

    public static final String GUARD_PLAN_READY = "ls.asset.planReady";
    public static final String GUARD_REPAIRS_DONE = "ls.asset.repairsDone";
    public static final String GUARD_VERIFIER = "ls.asset.verifierSeparation";

    private AssetPack() {
    }
}
