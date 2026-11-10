package com.lsmom.crud.metric;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 审计查询（只读命名 Query）：按业务对象或 trace_id 查看 Operation 审计、领域事件与 Outbox 投递，
 * 供验收走查做跨模块一致性核对，也供运维排查。权限沿用上游"操作日志"查询权限；数据域按操作人主属组织（事件按事件组织）注入。
 */
@Configuration
public class AuditQueries {

    static final String OBJECT = """
        {"type":"object","additionalProperties":false,"required":["concept","id"],
         "properties":{"concept":{"type":"string","maxLength":128},"id":{"type":"string","maxLength":64},
                       "limit":{"type":"integer","minimum":1,"maximum":500}}}""";
    static final String TRACE = """
        {"type":"object","additionalProperties":false,"required":["traceId"],
         "properties":{"traceId":{"type":"string","maxLength":64},"limit":{"type":"integer","minimum":1,"maximum":500}}}""";
    static final String PERMISSION = "monitor:operlog:list";

    /** 某业务对象上的 Operation 审计（含失败与拒绝），按时间先后。 */
    @Bean
    public NamedQueryDef auditObjectOperationsQuery() {
        return new NamedQueryDef("ls.audit.objectOperations", "对象的操作审计", "某业务对象上执行过的 Operation（含失败、拒绝），含操作人、结果、错误码与 trace_id",
            OBJECT, """
            select a.id, a.occurred_at as "occurredAt", a.op_code as "opCode", a.actor_type as "actorType", a.actor_party as "actorParty",
                   a.on_behalf_of as "onBehalfOf", a.result, a.error_code as "errorCode", a.data_version as "dataVersion",
                   a.trace_id as "traceId", a.approval_id as "approvalId"
              from op_audit a
              left join core_party_org po on po.party_id = a.actor_party and po.is_primary
             where a.target_concept = :concept and a.target_id = :id
               and {{scope:po.org_unit_id|a.actor_party}}
             order by a.occurred_at, a.id
             limit coalesce(cast(:limit as integer), 200)""", 5000, PERMISSION,
            List.of(NamedQueryRegistry.json(Map.of("concept", "ls.core.Work", "id", "1"))));
    }

    /** 某业务对象的领域事件与各目的地的 Outbox 投递状态。 */
    @Bean
    public NamedQueryDef auditObjectEventsQuery() {
        return new NamedQueryDef("ls.audit.objectEvents", "对象的领域事件与投递", "某业务对象的领域事件，以及每个事件在各 Outbox 目的地的投递状态",
            OBJECT, """
            select e.event_id as "eventId", e.type, e.occurred_at as "occurredAt", e.actor_party as "actorParty", e.trace_id as "traceId",
                   coalesce(jsonb_agg(jsonb_build_object('destination', o.destination, 'status', o.status, 'attempts', o.attempts)
                            order by o.destination) filter (where o.id is not null), '[]'::jsonb) as outbox
              from core_domain_event e
              left join core_outbox o on o.event_id = e.event_id
             where e.subject_concept = :concept and e.subject_id = :id
               and {{scope:e.org_unit_id|e.actor_party}}
             group by e.event_id, e.type, e.occurred_at, e.actor_party, e.trace_id
             order by e.occurred_at, e.event_id
             limit coalesce(cast(:limit as integer), 200)""", 5000, PERMISSION,
            List.of(NamedQueryRegistry.json(Map.of("concept", "ls.core.Work", "id", "1"))));
    }

    /** 同一 trace_id 经过的 Operation 与领域事件（跨模块追踪）。 */
    @Bean
    public NamedQueryDef auditTraceQuery() {
        return new NamedQueryDef("ls.audit.trace", "按 trace_id 追踪", "同一 trace_id 下执行的 Operation 与产生的领域事件，按时间排列",
            TRACE, """
            select x.kind, x.at, x.code, x.concept, x.target, x.result from (
              select 'OPERATION' as kind, a.occurred_at as at, a.op_code as code, a.target_concept as concept, a.target_id as target,
                     a.result, po.org_unit_id as org, a.actor_party as party
                from op_audit a left join core_party_org po on po.party_id = a.actor_party and po.is_primary
               where a.trace_id = :traceId
              union all
              select 'EVENT', e.occurred_at, e.type, e.subject_concept, e.subject_id, null, e.org_unit_id, e.actor_party
                from core_domain_event e where e.trace_id = :traceId
            ) x
             where {{scope:x.org|x.party}}
             order by x.at, x.kind
             limit coalesce(cast(:limit as integer), 200)""", 5000, PERMISSION,
            List.of(NamedQueryRegistry.json(Map.of("traceId", "0000000000000000"))));
    }
}
