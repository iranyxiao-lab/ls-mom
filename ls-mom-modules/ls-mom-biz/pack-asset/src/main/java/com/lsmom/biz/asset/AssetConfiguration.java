package com.lsmom.biz.asset;

import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.document.DocumentService;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.observation.ObservationService;
import com.lsmom.core.party.PartyService;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.util.Map;

/**
 * 设备 Pack 的代码部分：元数据（Concept、状态机、视图）在 {@code lsmom-packs/ls.asset}，由标准 Pack 安装器在启动时安装；
 * 这里提供迁移效果、守卫、按周期生成点检与设备时间轴。
 */
@Configuration(proxyBeanMethods = false)
public class AssetConfiguration {

    /** 关闭、驳回前的确认框内容：将结束的缺陷、同时关闭的关联问题、整改工作数量。 */
    @Bean
    public com.lsmom.action.core.IssueImpact defectImpact(ManagedRecords records, IssueService issues) {
        return new com.lsmom.action.core.IssueImpact(Map.of(AssetPack.DEFECT + ".verifyPass", "关闭", AssetPack.DEFECT + ".cancel", "取消"), "缺陷", id -> {
            Map<String, Object> r = records.find(AssetPack.DEFECT, Long.parseLong(id))
                .orElseThrow(() -> com.lsmom.core.error.LsBizException.notFound("缺陷", id));
            Object issue = r.get("issue");
            return new com.lsmom.action.core.IssueImpact.Target(String.valueOf(r.get("title")), issue == null ? null : ((Number) issue).longValue());
        }, issues);
    }

    @Bean
    public AssetEffects assetEffects(JdbcTemplate jdbc, ManagedRecords records, WorkItemService works, AssignmentService assignments,
                                     IssueService issues, ObservationService observations, DocumentService docs, PartyService parties,
                                     Clock clock) {
        return new AssetEffects(jdbc, records, works, assignments, issues, observations, docs, parties, clock);
    }

    @Bean
    public TransitionEffect assetCommissionEffect(AssetEffects e) {
        return e.status("commission", "RUNNING");
    }

    @Bean
    public TransitionEffect assetRunEffect(AssetEffects e) {
        return e.status("run", "RUNNING");
    }

    @Bean
    public TransitionEffect assetStandbyEffect(AssetEffects e) {
        return e.status("standby", "STANDBY");
    }

    @Bean
    public TransitionEffect assetMaintainEffect(AssetEffects e) {
        return e.status("maintain", "MAINTENANCE");
    }

    @Bean
    public TransitionEffect assetStopEffect(AssetEffects e) {
        return e.status("stop", "STOPPED");
    }

    @Bean
    public TransitionEffect assetScrapEffect(AssetEffects e) {
        return e.status("scrap", "SCRAPPED");
    }

    @Bean
    public TransitionEffect inspectionGenerateEffect(AssetEffects e) {
        return e.generate();
    }

    @Bean
    public TransitionEffect inspectionSubmitEffect(AssetEffects e) {
        return e.submit();
    }

    @Bean
    public TransitionEffect inspectionRaiseDefectsEffect(AssetEffects e) {
        return e.raiseDefects();
    }

    @Bean
    public TransitionEffect defectReportEffect(AssetEffects e) {
        return e.report();
    }

    @Bean
    public TransitionEffect defectCreateRepairEffect(AssetEffects e) {
        return e.createRepair();
    }

    @Bean
    public TransitionEffect defectSubmitVerifyEffect(AssetEffects e) {
        return e.submitVerify();
    }

    @Bean
    public TransitionEffect defectVerifyPassEffect(AssetEffects e) {
        return e.verifyPass();
    }

    @Bean
    public TransitionEffect defectVerifyFailEffect(AssetEffects e) {
        return e.verifyFail();
    }

    @Bean
    public TransitionEffect defectCancelEffect(AssetEffects e) {
        return e.cancel();
    }

    @Bean
    public Guard assetPlanReadyGuard(ManagedRecords records) {
        return AssetGuards.planReady(records);
    }

    @Bean
    public Guard assetRepairsDoneGuard(ManagedRecords records, IssueService issues) {
        return AssetGuards.repairsDone(records, issues);
    }

    @Bean
    public Guard assetVerifierGuard(ManagedRecords records, IssueService issues) {
        return AssetGuards.verifierSeparation(records, issues);
    }

    @Bean
    public AssetTimeline assetTimeline(JdbcTemplate jdbc, ManagedRecords records) {
        return new AssetTimeline(jdbc, records);
    }

    @Bean
    public InspectionScheduler inspectionScheduler(JdbcTemplate jdbc, ManagedRecords records, @Lazy OperationGateway gateway, Clock clock) {
        return new InspectionScheduler(jdbc, records, gateway, clock);
    }
}
