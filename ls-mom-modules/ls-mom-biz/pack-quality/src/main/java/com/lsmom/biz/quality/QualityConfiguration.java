package com.lsmom.biz.quality;

import com.lsmom.action.guard.Guard;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.meta.concept.PartyAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * 质量 Pack 的代码部分：元数据（Concept、状态机、规则、视图）在 {@code lsmom-packs/ls.quality}，由标准 Pack 安装器在启动时安装；
 * 条件必填与"终判仅限真人"是 Pack 规则，这里只提供迁移效果与跨对象守卫。
 */
@Configuration(proxyBeanMethods = false)
public class QualityConfiguration {

    @Bean
    public QualityEffects qualityEffects(ManagedRecords records, IssueService issues, WorkItemService works, AssignmentService assignments, Clock clock) {
        return new QualityEffects(records, issues, works, assignments, clock);
    }

    @Bean
    public TransitionEffect qualityRecordSubmitEffect(QualityEffects e) {
        return e.recordSubmit();
    }

    @Bean
    public TransitionEffect ncSubmitEffect(QualityEffects e) {
        return e.submit();
    }

    @Bean
    public TransitionEffect ncAuditEffect(QualityEffects e) {
        return e.audit();
    }

    @Bean
    public TransitionEffect ncRejectEffect(QualityEffects e) {
        return e.reject();
    }

    @Bean
    public TransitionEffect ncSubmitVerifyEffect(QualityEffects e) {
        return e.submitVerify();
    }

    @Bean
    public TransitionEffect ncCloseEffect(QualityEffects e) {
        return e.close();
    }

    @Bean
    public TransitionEffect ncVerifyFailEffect(QualityEffects e) {
        return e.verifyFail();
    }

    /** 整改负责人可见、可办理派给自己的不符合项（通常在班组，数据域不含车间级记录）。 */
    @Bean
    public PartyAccess ncRectifyOwnerAccess() {
        return new PartyAccess(QualityPack.NC, "rectifyOwner");
    }

    @Bean
    public Guard qualityRectificationDoneGuard(ManagedRecords records, IssueService issues) {
        return QualityGuards.rectificationDone(records, issues);
    }

    @Bean
    public Guard qualityEvidenceGuard(ManagedRecords records, IssueService issues, JdbcTemplate jdbc) {
        return QualityGuards.evidenceRequired(records, issues, jdbc);
    }

    @Bean
    public Guard qualityVerifierGuard(ManagedRecords records, IssueService issues) {
        return QualityGuards.verifierSeparation(records, issues);
    }
}
