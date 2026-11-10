package com.lsmom.flow;

import com.lsmom.action.gateway.OperationAudit;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.guard.Guard;
import com.lsmom.action.registry.OperationRegistry;
import com.lsmom.action.rule.CelRuleEngine;
import com.lsmom.action.state.StateStores;
import com.lsmom.core.event.EventPublisher;
import com.lsmom.core.org.LeaderResolver;
import com.lsmom.flow.engine.FlowEngineFactory;
import com.lsmom.flow.runtime.ApprovalEvidence;
import com.lsmom.flow.runtime.AssigneeResolver;
import com.lsmom.flow.runtime.FlowCallbacks;
import com.lsmom.flow.runtime.FlowGuards;
import com.lsmom.flow.runtime.FlowInstances;
import com.lsmom.flow.runtime.FlowNodePolicyResolver;
import com.lsmom.flow.runtime.FlowReconciler;
import com.lsmom.flow.runtime.FlowRegistry;
import com.lsmom.flow.runtime.FlowRuntime;
import com.lsmom.flow.runtime.TaskSync;
import com.lsmom.form.FormRegistry;
import com.lsmom.form.NodePolicyResolver;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldUsageIndex;
import org.flowable.engine.ProcessEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;

/**
 * 流程模块装配：嵌入式 Flowable（flowable schema、与业务共用数据源和事务）、流程定义、运行时、回调、对账与守卫。
 * {@code lsmom.flow.async-executor}（默认 true）控制定时器（超时提醒/升级）的异步执行器。
 */
@Configuration(proxyBeanMethods = false)
public class FlowConfiguration {

    @Bean
    public com.lsmom.flow.notice.Notices flowNotices(JdbcTemplate jdbc) {
        return new com.lsmom.flow.notice.Notices(jdbc);
    }

    @Bean
    public TaskSync flowTaskSync(JdbcTemplate jdbc) {
        return new TaskSync(jdbc);
    }

    @Bean(destroyMethod = "close")
    public ProcessEngine lsProcessEngine(DataSource dataSource, PlatformTransactionManager txm, TaskSync sync,
                                         @Value("${lsmom.flow.async-executor:true}") boolean async) {
        return FlowEngineFactory.build(dataSource, txm, new FlowEngineFactory.Options(async, null, List.of(sync)));
    }

    @Bean
    public FlowRegistry flowRegistry(JdbcTemplate jdbc, ProcessEngine engine, ConceptRegistry concepts, OperationRegistry operations,
                                     FormRegistry forms, FieldUsageIndex usages, AssigneeResolver assignees, CelRuleEngine cel,
                                     PlatformTransactionManager txm) {
        return new FlowRegistry(jdbc, engine, concepts, operations, forms, usages, assignees, cel, txm);
    }

    @Bean
    public FlowInstances flowInstances(JdbcTemplate jdbc) {
        return new FlowInstances(jdbc);
    }

    @Bean
    public FlowCallbacks flowCallbacks(JdbcTemplate jdbc, OperationGateway gateway, StateStores stores, PlatformTransactionManager txm, Clock clock) {
        return new FlowCallbacks(jdbc, gateway, stores, txm, clock);
    }

    @Bean
    public ApprovalEvidence approvalEvidence(JdbcTemplate jdbc, StateStores stores) {
        return new ApprovalEvidence(jdbc, stores);
    }

    @Bean
    public Guard flowApprovalValidGuard(ApprovalEvidence evidence) {
        return evidence.guard();
    }

    @Bean
    public Guard flowNotHandledGuard(JdbcTemplate jdbc) {
        return FlowGuards.notHandled(jdbc);
    }

    @Bean
    public NodePolicyResolver flowNodePolicyResolver() {
        return new FlowNodePolicyResolver();
    }

    @Bean
    public FlowRuntime flowRuntime(JdbcTemplate jdbc, ProcessEngine engine, FlowRegistry registry, FlowInstances instances, FlowCallbacks callbacks,
                                   ApprovalEvidence evidence, AssigneeResolver assignees, OperationGateway gateway, OperationAudit audit,
                                   StateStores stores, CelRuleEngine cel, EventPublisher events, LeaderResolver leaders, FormRegistry forms,
                                   ConceptRegistry concepts, TaskSync sync, PlatformTransactionManager txm, Clock clock,
                                   @Value("${lsmom.flow.urge-min-age-minutes:0}") long urgeMinAge) {
        FlowRuntime r = new FlowRuntime(jdbc, engine, registry, instances, callbacks, evidence, assignees, gateway, audit, stores, cel, events, leaders,
            forms, concepts, sync, txm, clock);
        r.setUrgeMinAge(java.time.Duration.ofMinutes(urgeMinAge));
        return r;
    }

    @Bean
    public com.lsmom.flow.runtime.WorkCenter workCenter(JdbcTemplate jdbc, FlowRuntime runtime, FlowRegistry registry, FormRegistry forms,
                                                        ConceptRegistry concepts, com.lsmom.crud.GenericCrudService crud, OperationGateway gateway,
                                                        com.lsmom.action.timeline.TimelineService timeline, Clock clock) {
        return new com.lsmom.flow.runtime.WorkCenter(jdbc, runtime, registry, forms, concepts, crud, gateway, timeline, clock);
    }

    @Bean
    public FlowReconciler flowReconciler(JdbcTemplate jdbc, ProcessEngine engine, FlowRuntime runtime, PlatformTransactionManager txm) {
        return new FlowReconciler(jdbc, engine, runtime, txm);
    }
}
