package com.lsmom.pack;

import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.rule.RulePublisher;
import com.lsmom.action.state.StateMachineRegistry;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.crud.ddl.LedgerPublisher;
import com.lsmom.crud.metric.MetricRegistry;
import com.lsmom.flow.runtime.FlowRegistry;
import com.lsmom.form.FormRegistry;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.pack.PackGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/** Pack 生命周期装配。 */
@Configuration(proxyBeanMethods = false)
public class PackConfiguration {

    @Bean
    public PackGate packGate(JdbcTemplate jdbc) {
        return new PackGate(jdbc);
    }

    @Bean
    public PackStore packStore(JdbcTemplate jdbc) {
        return new PackStore(jdbc);
    }

    @Bean
    public PackDefinitions packDefinitions(JdbcTemplate jdbc, ConceptRegistry concepts) {
        return new PackDefinitions(jdbc, concepts);
    }

    @Bean
    public PackInstaller packInstaller(JdbcTemplate jdbc, PackStore store, PackDefinitions defs, ConceptRegistry concepts, LedgerPublisher ledgers,
                                       StateMachineRegistry machines, RulePublisher rules, FormRegistry forms, MetricRegistry metrics,
                                       FlowRegistry flows, GenericCrudService crud, PackGate gate) {
        return new PackInstaller(jdbc, store, defs, concepts, ledgers, machines, rules, forms, metrics, flows, crud, gate);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "lsmom.pack.standard.enabled", havingValue = "true", matchIfMissing = true)
    public StandardPacks standardPacks(PackInstaller installer, PackStore store) {
        return new StandardPacks(installer, store);
    }

    @Bean
    public PackLifecycle packLifecycle(JdbcTemplate jdbc, PackStore store, PackGate gate) {
        return new PackLifecycle(jdbc, store, gate);
    }

    @Bean
    public PackTester packTester(JdbcTemplate jdbc, PackStore store, GenericCrudService crud, OperationGateway gateway, OperationRegistry operations,
                                 ConceptRegistry concepts, PlatformTransactionManager txm) {
        return new PackTester(jdbc, store, crud, gateway, operations, concepts, txm);
    }

    @Bean
    public ChangeSetPipeline changeSetPipeline(JdbcTemplate jdbc, PackStore store, PackInstaller installer, PackDefinitions defs) {
        return new ChangeSetPipeline(jdbc, store, installer, defs);
    }

    @Bean
    public DriftDetector driftDetector(PackStore store, PackDefinitions defs, ChangeSetPipeline pipeline) {
        return new DriftDetector(store, defs, pipeline);
    }

    @Bean
    public PackReview packReview(JdbcTemplate jdbc, PackStore store, Clock clock) {
        return new PackReview(jdbc, store, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "lsmom.pack.jobs", havingValue = "true", matchIfMissing = true)
    public PackJobs packJobs(PackReview review) {
        return new PackJobs(review);
    }

    /** 每日复审检查（到期待复审、超期未用建议下线并通知负责人）。 */
    public static class PackJobs {

        private static final Logger log = LoggerFactory.getLogger(PackJobs.class);
        private final PackReview review;

        public PackJobs(PackReview review) {
            this.review = review;
        }

        @Scheduled(cron = "${lsmom.pack.review-cron:0 30 7 * * *}")
        public void review() {
            var changes = review.review();
            if (!changes.isEmpty()) log.info("Pack 复审状态变化：{}", changes);
        }
    }
}
