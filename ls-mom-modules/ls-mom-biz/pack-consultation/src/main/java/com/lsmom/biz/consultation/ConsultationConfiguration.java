package com.lsmom.biz.consultation;

import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.issue.IssueService;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * 意见咨询 Pack 的代码部分：元数据（Concept、状态机、规则、表单）在 {@code lsmom-packs/ls.consultation}，由标准 Pack 安装器在启动时安装；
 * 这里提供迁移效果、提交意见 / 草拟总结 Operation、匿名时间轴脱敏与到期截止。核心模块不引用本 Pack 的任何类型；不依赖培训考试 Pack。
 */
@Configuration(proxyBeanMethods = false)
public class ConsultationConfiguration {

    @Bean
    public ConsultationEffects consultationEffects(ManagedRecords records, IssueService issues, Clock clock) {
        return new ConsultationEffects(records, issues, clock);
    }

    @Bean
    public TransitionEffect consultationTopicOpenEffect(ConsultationEffects e) {
        return e.open();
    }

    @Bean
    public TransitionEffect consultationTopicCloseEffect(ConsultationEffects e) {
        return e.close();
    }

    @Bean
    public TransitionEffect consultationReplyEffect(ConsultationEffects e) {
        return e.reply();
    }

    @Bean
    public TransitionEffect consultationToIssueEffect(ConsultationEffects e) {
        return e.toIssue();
    }

    @Bean
    public TransitionEffect consultationSummaryPublishEffect(ConsultationEffects e) {
        return e.publishSummary();
    }

    @Bean
    public ConsultationOperations.SubmitOpinion consultationSubmitOpinion(JdbcTemplate jdbc, ManagedRecords records, Clock clock) {
        return new ConsultationOperations.SubmitOpinion(jdbc, records, clock);
    }

    @Bean
    public ConsultationOperations.DraftSummary consultationDraftSummary(ManagedRecords records) {
        return new ConsultationOperations.DraftSummary(records);
    }

    @Bean
    public AnonymousTimeline consultationAnonymousTimeline(ManagedRecords records) {
        return new AnonymousTimeline(records);
    }

    @Bean
    public ConsultationScheduler consultationScheduler(JdbcTemplate jdbc, ManagedRecords records, OperationGateway gateway, Clock clock) {
        return new ConsultationScheduler(jdbc, records, gateway, clock);
    }
}
