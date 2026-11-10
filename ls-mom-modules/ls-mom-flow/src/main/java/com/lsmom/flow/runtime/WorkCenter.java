package com.lsmom.flow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.OperationGateway;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.core.error.LsBizException;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.flow.bpmn.BpmnTemplateGenerator;
import com.lsmom.flow.model.FlowDefinition;
import com.lsmom.form.FormRegistry;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import com.lsmom.meta.concept.FieldDef;
import com.lsmom.meta.concept.MetaNames;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 协同工作中心读模型：我的待办（超时、即将到期在前）、已办、我发起的、抄送我的、委托记录、通知消息，以及在待办中办理所需的上下文。
 * 通知已读与待办完成相互独立：读消息不改变待办，待办只在办理后消失。
 */
public class WorkCenter {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** 距到期不足该时长视为「即将到期」。 */
    public static final java.time.Duration DUE_SOON = java.time.Duration.ofHours(24);

    public enum Urgency { OVERDUE, DUE_SOON, NORMAL }

    public record Filter(String bizType, Urgency urgency, String keyword) {
        public static Filter none() {
            return new Filter(null, null, null);
        }
    }

    public record Item(FlowRuntime.TaskView task, Urgency urgency, String flowCode, Integer flowVersion, Long initiatorParty, String initiatorName,
                       String bizTitle) {
    }

    private final JdbcTemplate jdbc;
    private final FlowRuntime runtime;
    private final FlowRegistry registry;
    private final FormRegistry forms;
    private final ConceptRegistry concepts;
    private final GenericCrudService crud;
    private final OperationGateway gateway;
    private final TimelineService timeline;
    private final Clock clock;

    public WorkCenter(JdbcTemplate jdbc, FlowRuntime runtime, FlowRegistry registry, FormRegistry forms, ConceptRegistry concepts,
                      GenericCrudService crud, OperationGateway gateway, TimelineService timeline, Clock clock) {
        this.jdbc = jdbc;
        this.runtime = runtime;
        this.registry = registry;
        this.forms = forms;
        this.concepts = concepts;
        this.crud = crud;
        this.gateway = gateway;
        this.timeline = timeline;
        this.clock = clock;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static final String ITEM_SELECT = """
        select t.*, i.flow_code as i_flow_code, i.flow_version as i_flow_version, i.initiator_party as i_initiator, p.name as i_initiator_name
          from biz_task t
          left join biz_flow_instance i on i.id = t.flow_instance_id
          left join core_party p on p.id = i.initiator_party
        """;

    /** 我的待办：办理人或候选人；超时在前，其次即将到期，再按到期时间、创建时间。 */
    public List<Item> todo(Long party, Filter f) {
        List<Object> args = new ArrayList<>(List.of(party, party));
        String where = filters(f, args, true);
        args.add(now());
        args.add(now().plus(DUE_SOON));
        return jdbc.query(ITEM_SELECT + """
             where t.status = 'TODO' and t.kind in ('TASK', 'EXCEPTION', 'ESCALATION')
               and (t.assignee_party = ? or (t.assignee_party is null and t.candidates @> to_jsonb(?::bigint)))
            """ + where + """

             order by case when t.due_at < ? then 0 when t.due_at < ? then 1 else 2 end, t.due_at nulls last, t.created_at, t.id
             limit 500""", (rs, i) -> item(rs), args.toArray());
    }

    public List<Item> done(Long party, Filter f) {
        List<Object> args = new ArrayList<>(List.of(party));
        return jdbc.query(ITEM_SELECT + " where t.status = 'DONE' and t.kind in ('TASK', 'EXCEPTION') and t.completed_by = ? "
            + filters(f, args, false) + " order by t.completed_at desc, t.id desc limit 500", (rs, i) -> item(rs), args.toArray());
    }

    public List<Item> cc(Long party, Filter f) {
        List<Object> args = new ArrayList<>(List.of(party));
        return jdbc.query(ITEM_SELECT + " where t.kind = 'CC' and t.assignee_party = ? " + filters(f, args, false)
            + " order by t.status desc, t.created_at desc, t.id desc limit 500", (rs, i) -> item(rs), args.toArray());
    }

    /** 我发起的流程：当前节点、状态。 */
    public List<Map<String, Object>> started(Long party, Filter f) {
        List<Object> args = new ArrayList<>(List.of(party));
        StringBuilder w = new StringBuilder();
        if (f.bizType() != null) {
            w.append(" and i.biz_type = ?");
            args.add(f.bizType());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select i.id, i.flow_code as "flowCode", i.flow_version as "flowVersion", i.biz_type as "bizType", i.biz_id as "bizId",
                   i.status, i.sync_status as "syncStatus", i.started_at as "startedAt", i.ended_at as "endedAt", i.proc_inst_id as "procInstId",
                   (select string_agg(distinct t.node_name, '、') from biz_task t where t.proc_inst_id = i.proc_inst_id and t.status = 'TODO'
                      and t.kind in ('TASK', 'EXCEPTION')) as "currentNodes",
                   (select min(t.task_id) from biz_task t where t.proc_inst_id = i.proc_inst_id and t.status = 'TODO'
                      and t.kind in ('TASK', 'EXCEPTION')) as "currentTaskId"
              from biz_flow_instance i where i.initiator_party = ?""" + w + " order by i.started_at desc, i.id desc limit 500", args.toArray());
        for (Map<String, Object> r : rows) r.put("bizTitle", title((String) r.get("bizType"), (String) r.get("bizId")));
        return rows;
    }

    /** 委托记录：我转出的与转给我的。 */
    public List<Map<String, Object>> delegations(Long party) {
        return jdbc.queryForList("""
            select x.id, x.task_id as "taskId", x.biz_type as "bizType", x.biz_id as "bizId", x.node_name as "nodeName",
                   x.from_party as "fromParty", pf.name as "fromName", x.to_party as "toParty", pt.name as "toName", x.reason,
                   x.created_at as "createdAt", case when x.from_party = ? then 'OUT' else 'IN' end as direction,
                   t.status as "taskStatus"
              from biz_task_transfer x
              left join core_party pf on pf.id = x.from_party
              left join core_party pt on pt.id = x.to_party
              left join biz_task t on t.task_id = x.task_id
             where x.from_party = ? or x.to_party = ?
             order by x.created_at desc, x.id desc limit 500""", party, party, party);
    }

    public List<Map<String, Object>> messages(Long party, Boolean unreadOnly) {
        return jdbc.queryForList("""
            select n.id, n.kind, n.task_id as "taskId", n.biz_type as "bizType", n.biz_id as "bizId", n.message, n.created_at as "createdAt",
                   n.read_at as "readAt", p.name as "fromName"
              from biz_flow_notice n left join core_party p on p.id = n.created_by
             where n.party_id = ?""" + (Boolean.TRUE.equals(unreadOnly) ? " and n.read_at is null" : "")
            + " order by n.id desc limit 200", party);
    }

    /**
     * 通知抽屉：按"待办提醒 / 系统消息 / @我"分组，每组最近 50 条（未读在前），带未读数。
     * 带动作的通知（action）可在抽屉内直接完成；已办结的显示为已处理。
     */
    public Map<String, Object> notifications(Long party) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> unread = new LinkedHashMap<>();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("todo", com.lsmom.flow.notice.Notices.TODO_KINDS);
        groups.put("system", com.lsmom.flow.notice.Notices.SYSTEM_KINDS);
        groups.put("mention", com.lsmom.flow.notice.Notices.MENTION_KINDS);
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            String[] kinds = g.getValue().toArray(String[]::new);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                select n.id, n.kind, n.title, n.message, n.link, n.action::text as action, n.task_id as "taskId", n.biz_type as "bizType",
                       n.biz_id as "bizId", n.created_at as "createdAt", n.read_at as "readAt", n.done_at as "doneAt", p.name as "fromName"
                  from biz_flow_notice n left join core_party p on p.id = n.created_by
                 where n.party_id = ? and n.kind = any(?)
                 order by (n.read_at is null) desc, n.id desc limit 50""", party, kinds);
            for (Map<String, Object> r : rows) {
                Object a = r.get("action");
                try {
                    r.put("action", a == null ? null : JSON.readTree((String) a));
                } catch (Exception e) {
                    r.put("action", null);
                }
                // 流程待办类通知没有单独地址：打开协同工作中心
                if (r.get("link") == null && r.get("taskId") != null) r.put("link", "/lsmom/workbench");
            }
            out.put(g.getKey(), rows);
            unread.put(g.getKey(), jdbc.queryForObject("select count(*) from biz_flow_notice where party_id = ? and read_at is null and kind = any(?)",
                Long.class, party, kinds));
        }
        out.put("unread", unread);
        return out;
    }

    /** 标记消息已读：只改消息，不影响任何待办。 */
    public int readMessages(Long party, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return jdbc.update("update biz_flow_notice set read_at = ? where party_id = ? and read_at is null", now(), party);
        }
        return jdbc.update("update biz_flow_notice set read_at = ? where party_id = ? and read_at is null and id = any(?)", now(), party,
            ids.toArray(Long[]::new));
    }

    public List<Map<String, Object>> parties(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        return jdbc.queryForList("""
            select p.id, p.name, o.name as "orgName"
              from core_party p
              left join core_party_org po on po.party_id = p.id and po.is_primary
              left join core_org_unit o on o.id = po.org_unit_id
             where p.party_type = 'EMPLOYEE' and p.status = 'ACTIVE' and (? = '' or p.name ilike ?)
             order by p.id limit 30""", k, "%" + k + "%");
    }

    public Map<String, Object> counts(Long party) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("todo", jdbc.queryForObject("""
            select count(*) from biz_task t where t.status = 'TODO' and t.kind in ('TASK', 'EXCEPTION', 'ESCALATION')
              and (t.assignee_party = ? or (t.assignee_party is null and t.candidates @> to_jsonb(?::bigint)))""", Long.class, party, party));
        m.put("overdue", jdbc.queryForObject("""
            select count(*) from biz_task t where t.status = 'TODO' and t.kind in ('TASK', 'EXCEPTION', 'ESCALATION') and t.due_at < ?
              and (t.assignee_party = ? or (t.assignee_party is null and t.candidates @> to_jsonb(?::bigint)))""", Long.class, now(), party, party));
        m.put("ccUnread", jdbc.queryForObject("select count(*) from biz_task where kind = 'CC' and status = 'TODO' and assignee_party = ?",
            Long.class, party));
        m.put("messagesUnread", jdbc.queryForObject("select count(*) from biz_flow_notice where party_id = ? and read_at is null", Long.class, party));
        return m;
    }

    /** 在待办中办理所需的上下文：表单（冻结版本）、节点策略、业务记录、可退回节点、时间轴与版本快照。 */
    public Map<String, Object> context(String taskId, ActorContext actor) {
        FlowRuntime.TaskView t = runtime.task(taskId);
        FlowInstances.Instance inst = runtime.instances().get(t.flowInstanceId());
        Long me = actor.effectiveParty();
        boolean handler = Objects.equals(t.assigneeParty(), me) || (t.assigneeParty() == null && t.candidates().contains(me));
        boolean initiator = Objects.equals(inst.initiatorParty(), me);
        if (!handler && !initiator && !actor.superAdmin()) throw new LsBizException("ACCESS_DENIED", "你不是该待办的办理人或发起人");
        FlowDefinition def = registry.get(inst.flowCode(), inst.flowVersion()).definition();
        FlowDefinition.Node node = def.node(t.nodeKey());
        boolean initiatorNode = BpmnTemplateGenerator.INITIATOR.equals(t.nodeKey());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task", t);
        m.put("urgency", urgency(t.dueAt()));
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("id", inst.id());
        in.put("flowCode", inst.flowCode());
        in.put("flowName", def.name());
        in.put("flowVersion", inst.flowVersion());
        in.put("formCode", inst.formCode());
        in.put("formVersion", inst.formVersion());
        in.put("initiatorParty", inst.initiatorParty());
        in.put("initiatorName", partyName(inst.initiatorParty()));
        in.put("status", inst.status());
        in.put("startedAt", inst.startedAt());
        m.put("instance", in);
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("key", t.nodeKey());
        n.put("name", t.nodeName());
        n.put("type", node == null ? (initiatorNode ? "INITIATOR" : null) : node.type());
        n.put("mode", node == null ? null : node.mode());
        // 发起人（被退回）待办：再提交 Operation 携带按 nodePolicies.initiator 可编辑的字段
        String nodeOp = node != null ? node.operation() : initiatorNode && def.callbacks() != null ? def.callbacks().resubmitted() : null;
        n.put("operation", nodeOp);
        n.put("inputSchema", nodeOp == null ? null : gateway.operationSchema(nodeOp));
        m.put("node", n);
        boolean open = "TODO".equals(t.status()) && handler && !"CC".equals(t.kind());
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("approve", open && !initiatorNode);
        actions.put("reject", open && !initiatorNode);
        actions.put("return", open && !initiatorNode);
        actions.put("transfer", open);
        actions.put("resubmit", open && initiatorNode);
        actions.put("urge", "TODO".equals(t.status()) && (initiator || actor.superAdmin()) && !"CC".equals(t.kind()));
        m.put("actions", actions);
        List<Map<String, Object>> targets = new ArrayList<>();
        targets.add(Map.of("key", BpmnTemplateGenerator.INITIATOR, "name", "发起人"));
        if (inst.procInstId() != null) {
            for (Map<String, Object> r : jdbc.queryForList("""
                select node_key, max(node_name) as node_name, min(created_at) as first_at from biz_task
                 where proc_inst_id = ? and kind = 'TASK' and status = 'DONE' and node_key <> ? and node_key <> ?
                 group by node_key order by first_at""", inst.procInstId(), t.nodeKey(), BpmnTemplateGenerator.INITIATOR)) {
                targets.add(Map.of("key", r.get("node_key"), "name", r.get("node_name")));
            }
        }
        m.put("returnTargets", targets);
        JsonNode form = null;
        if (inst.formCode() != null && inst.formVersion() != null) {
            try {
                form = forms.get(inst.formCode(), inst.formVersion()).schema();
            } catch (LsBizException e) {
                form = null;
            }
        }
        m.put("form", form);
        JsonNode policy = node == null ? (initiatorNode && form != null ? form.path("nodePolicies").get(BpmnTemplateGenerator.INITIATOR) : null)
            : node.fieldPolicy() != null ? node.fieldPolicy()
            : form == null || node.policyKey() == null ? null : form.path("nodePolicies").get(node.policyKey());
        m.put("fieldPolicy", policy);
        Map<String, Object> record = record(inst.bizType(), inst.bizId(), actor);
        m.put("record", record);
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("flowCode", inst.flowCode());
        snap.put("flowVersion", inst.flowVersion());
        snap.put("formCode", inst.formCode());
        snap.put("formVersion", inst.formVersion());
        snap.put("taskFormVersion", t.formVersion());
        snap.put("recordStatus", record.get("status"));
        snap.put("recordVersion", record.get("version"));
        m.put("snapshot", snap);
        m.put("timeline", timeline.of(inst.bizType(), inst.bizId()));
        return m;
    }

    /** 业务记录（调用前已确认调用者是办理人或发起人）：台账走通用 CRUD（字段读权限）；核心对象按元数据读核心表。 */
    Map<String, Object> record(String bizType, String bizId, ActorContext actor) {
        ConceptDef c = concepts.get(bizType);
        if (c.storage() == ConceptDef.Storage.MANAGED_TABLE) {
            // 待办授予办理人/发起人查看该单据的权利：补上查询权限与数据域，字段级读权限仍按调用者本人判定
            java.util.Set<String> perms = new java.util.HashSet<>(actor.permissions());
            perms.add(GenericCrudService.permission(bizType, "query"));
            ActorContext reader = new ActorContext(ActorContext.ActorType.HUMAN, actor.userId(), actor.actorParty(), actor.onBehalfOf(),
                actor.superAdmin(), perms, com.lsmom.core.org.DataScope.unrestricted(), actor.displayName());
            return crud.get(bizType, Long.parseLong(bizId), reader);
        }
        List<String> cols = new ArrayList<>();
        for (FieldDef f : c.fields()) {
            if (f.status() == FieldDef.FieldStatus.REMOVED || f.ext()) continue;
            cols.add("\"" + MetaNames.requireIdentifier(f.columnName()) + "\" as \"" + f.code() + "\"");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("select " + String.join(", ", cols) + " from \""
            + MetaNames.requireIdentifier(c.tableName()) + "\" where id = ?", Long.parseLong(bizId));
        if (rows.isEmpty()) throw LsBizException.notFound(c.displayName(), bizId);
        return rows.get(0);
    }

    private String filters(Filter f, List<Object> args, boolean withUrgency) {
        StringBuilder w = new StringBuilder();
        if (f == null) return "";
        if (f.bizType() != null && !f.bizType().isBlank()) {
            w.append(" and t.biz_type = ?");
            args.add(f.bizType());
        }
        if (f.keyword() != null && !f.keyword().isBlank()) {
            w.append(" and (t.node_name ilike ? or t.biz_id = ? or i.flow_code ilike ?)");
            String k = "%" + f.keyword().trim() + "%";
            args.add(k);
            args.add(f.keyword().trim());
            args.add(k);
        }
        if (withUrgency && f.urgency() != null) {
            switch (f.urgency()) {
                case OVERDUE -> {
                    w.append(" and t.due_at < ?");
                    args.add(now());
                }
                case DUE_SOON -> {
                    w.append(" and t.due_at >= ? and t.due_at < ?");
                    args.add(now());
                    args.add(now().plus(DUE_SOON));
                }
                case NORMAL -> {
                    w.append(" and (t.due_at is null or t.due_at >= ?)");
                    args.add(now().plus(DUE_SOON));
                }
            }
        }
        return w.append(' ').toString();
    }

    private Item item(java.sql.ResultSet rs) throws java.sql.SQLException {
        FlowRuntime.TaskView t = FlowRuntime.view(rs);
        return new Item(t, urgency(t.dueAt()), rs.getString("i_flow_code"), (Integer) rs.getObject("i_flow_version"),
            (Long) rs.getObject("i_initiator"), rs.getString("i_initiator_name"), title(t.bizType(), t.bizId()));
    }

    Urgency urgency(LocalDateTime due) {
        if (due == null) return Urgency.NORMAL;
        if (due.isBefore(now())) return Urgency.OVERDUE;
        if (due.isBefore(now().plus(DUE_SOON))) return Urgency.DUE_SOON;
        return Urgency.NORMAL;
    }

    /** 业务对象显示名：取 title / name / code / 标签字段之一（失败返回空，不影响列表）。 */
    private String title(String bizType, String bizId) {
        try {
            Optional<ConceptDef> c = concepts.find(bizType);
            if (c.isEmpty() || !bizId.matches("\\d+")) return null;
            String col = null;
            for (String k : List.of("title", "name", "code")) {
                for (FieldDef f : c.get().fields()) if (f.code().equals(k) && !f.ext()) col = f.columnName();
                if (col != null) break;
            }
            // 其次：标记为显示标签的字段，再其次：唯一编号字段（如单号）
            for (String flag : List.of("label", "unique")) {
                if (col != null) break;
                for (FieldDef f : c.get().fields()) if (col == null && f.flag(flag) && !f.ext()) col = f.columnName();
            }
            if (col == null) return null;
            return jdbc.queryForList("select \"" + MetaNames.requireIdentifier(col) + "\"::text from \"" + MetaNames.requireIdentifier(c.get().tableName())
                + "\" where id = ?", String.class, Long.parseLong(bizId)).stream().findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String partyName(Long id) {
        return id == null ? null : jdbc.queryForList("select name from core_party where id = ?", String.class, id).stream().findFirst().orElse(null);
    }
}
