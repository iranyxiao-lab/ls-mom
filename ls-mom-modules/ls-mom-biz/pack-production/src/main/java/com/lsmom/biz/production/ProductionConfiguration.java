package com.lsmom.biz.production;

import com.lsmom.meta.concept.ManagedRecords;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.state.TransitionEffect;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.meta.concept.DraftPrivacy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/**
 * 轻生产 Pack 的代码部分：Pack 元数据（Concept、状态机、视图、指标）在 {@code lsmom-packs/ls.production}，
 * 由标准 Pack 安装器在启动时安装；这里只提供状态机迁移所需的业务效果与守卫。
 */
@Configuration(proxyBeanMethods = false)
public class ProductionConfiguration {

    @Bean
    public HandoverSummarizer handoverSummarizer() {
        return new HandoverSummarizer();
    }

    @Bean
    public ProductionEffects productionEffects(JdbcTemplate jdbc, ManagedRecords records, WorkItemService works,
                                               AssignmentService assignments, HandoverSummarizer summarizer, Clock clock) {
        return new ProductionEffects(jdbc, records, works, assignments, summarizer, clock);
    }

    @Bean
    public TransitionEffect productionRequestConvertEffect(ProductionEffects e) {
        return e.convert();
    }

    @Bean
    public TransitionEffect productionRequestCancelEffect(ProductionEffects e) {
        return e.reason(ProductionPack.REQUEST + ".cancel");
    }

    @Bean
    public TransitionEffect shiftPlanActivateEffect(ProductionEffects e) {
        return e.activate();
    }

    @Bean
    public TransitionEffect shiftPlanMoveEffect(ProductionEffects e) {
        return e.move();
    }

    @Bean
    public TransitionEffect handoverGenerateEffect(ProductionEffects e) {
        return e.generate();
    }

    @Bean
    public TransitionEffect handoverPublishEffect(ProductionEffects e) {
        return e.publish();
    }

    @Bean
    public TransitionEffect handoverAcceptEffect(ProductionEffects e) {
        return e.accept();
    }

    @Bean
    public TransitionEffect handoverReturnEffect(ProductionEffects e) {
        return e.reason(ProductionPack.HANDOVER + ".returnBack");
    }

    @Bean
    public Guard handoverOwnerGuard(ManagedRecords records) {
        return HandoverGuards.owner(records);
    }

    @Bean
    public Guard handoverReceiverGuard(ManagedRecords records, JdbcTemplate jdbc) {
        return HandoverGuards.receiver(records, jdbc);
    }

    /** 交接班草稿只有交班人可见：交班人确认（发布）前，接班人看不到。 */
    @Bean
    public DraftPrivacy handoverDraftPrivacy() {
        return new DraftPrivacy(ProductionPack.HANDOVER, "DRAFT");
    }
}
