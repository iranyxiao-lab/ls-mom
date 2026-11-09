package com.lsmom.biz.quality;

import java.util.Map;

/** 质量 Pack 的编码常量。 */
public final class QualityPack {

    public static final String CODE = "ls.quality";
    public static final String RECORD = "ls.quality.QualityRecord";
    public static final String RECORD_LINK = "ls.quality.QualityRecordLink";
    public static final String NC = "ls.quality.NonConformance";
    public static final String ISSUE_TYPE = "NONCONFORMITY";

    public static final String GUARD_RECTIFIED = "ls.quality.rectificationDone";
    public static final String GUARD_EVIDENCE = "ls.quality.evidenceRequired";
    public static final String GUARD_VERIFIER = "ls.quality.verifierSeparation";

    /** 关联对象类型 → 时间轴主体（领域事件的 subject_concept）。 */
    public static final Map<String, String> SUBJECTS = Map.of(
        "WORK", "ls.core.Work",
        "ASSET", "ls.asset.Asset",
        "OUTPUT", "ls.production.ProductionOutput",
        "MATERIAL", "ls.production.MaterialRecord",
        "EXTERNAL", "external");

    /** 审核节点允许填写的输入（其余记录字段只读）。 */
    public static final java.util.Set<String> AUDIT_INPUT = java.util.Set.of("auditOpinion", "immediateAction", "owner", "titles", "dueAt");

    private QualityPack() {
    }
}
