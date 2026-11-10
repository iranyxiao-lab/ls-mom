package com.lsmom.biz.training;

import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.meta.concept.PartyAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * 培训考试 Pack 的代码部分：元数据（Concept、状态机、规则、发布审批流）在 {@code lsmom-packs/ls.training}，由标准 Pack 安装器在启动时安装；
 * 这里提供迁移效果、开考 / 草拟 / 资质查询 Operation 与超时交卷。核心模块不引用本 Pack 的任何类型。
 */
@Configuration(proxyBeanMethods = false)
public class TrainingConfiguration {

    @Bean
    public TrainingEffects trainingEffects(ManagedRecords records, Clock clock) {
        return new TrainingEffects(records, clock);
    }

    @Bean
    public TransitionEffect trainingComposeEffect(TrainingEffects e) {
        return e.compose();
    }

    @Bean
    public TransitionEffect trainingExamSubmitEffect(TrainingEffects e) {
        return e.examSubmit();
    }

    @Bean
    public TransitionEffect trainingAnswerSaveEffect(TrainingEffects e) {
        return e.save();
    }

    @Bean
    public TransitionEffect trainingAnswerSubmitEffect(TrainingEffects e) {
        return e.submit();
    }

    @Bean
    public TransitionEffect trainingGradeEssayEffect(TrainingEffects e) {
        return e.gradeEssay();
    }

    @Bean
    public TrainingOperations.StartExam trainingStartExam(ManagedRecords records, Clock clock) {
        return new TrainingOperations.StartExam(records, clock);
    }

    @Bean
    public TrainingOperations.DraftQuestions trainingDraftQuestions(ManagedRecords records) {
        return new TrainingOperations.DraftQuestions(records);
    }

    @Bean
    public TrainingOperations.Qualification trainingQualification(JdbcTemplate jdbc, ManagedRecords records, Clock clock) {
        return new TrainingOperations.Qualification(jdbc, records, clock);
    }

    /** 考生可见、可办理自己的答卷（考生多在班组，数据域不含考试所属组织）。 */
    @Bean
    public PartyAccess trainingAnswerCandidateAccess() {
        return new PartyAccess(TrainingPack.ANSWER, "candidate");
    }

    /** 培训记录本人可见。 */
    @Bean
    public PartyAccess trainingRecordOwnerAccess() {
        return new PartyAccess(TrainingPack.RECORD, "party");
    }

    @Bean
    public TrainingScheduler trainingScheduler(JdbcTemplate jdbc, ManagedRecords records, OperationGateway gateway, Clock clock) {
        return new TrainingScheduler(jdbc, records, gateway, clock);
    }
}
