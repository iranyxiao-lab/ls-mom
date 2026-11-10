package com.lsmom.biz.consultation;

/** 意见咨询 Pack 的编码常量。核心中不出现这些概念（DOM-05）。 */
public final class ConsultationPack {

    public static final String CODE = "ls.consultation";
    public static final String TOPIC = "ls.consultation.Topic";
    public static final String OPINION = "ls.consultation.Opinion";
    public static final String SUMMARY = "ls.consultation.Summary";
    public static final String POINT = "ls.consultation.SummaryPoint";

    public static final String SUBMIT = "ls.consultation.Opinion.submit";
    public static final String DRAFT_SUMMARY = "ls.consultation.Summary.draft";

    /** 可看到匿名意见提交主体（审计）的权限。 */
    public static final String AUDIT = "ls:consultation:audit";
    /** 意见转整改生成的 Issue 类型与来源。 */
    public static final String ISSUE_TYPE = "CONSULTATION";

    private ConsultationPack() {
    }
}
