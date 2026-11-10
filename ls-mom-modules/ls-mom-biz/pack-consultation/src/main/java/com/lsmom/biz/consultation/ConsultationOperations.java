package com.lsmom.biz.consultation;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ManagedRecords;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lsmom.biz.consultation.ConsultationPack.OPINION;
import static com.lsmom.biz.consultation.ConsultationPack.POINT;
import static com.lsmom.biz.consultation.ConsultationPack.SUMMARY;
import static com.lsmom.biz.consultation.ConsultationPack.TOPIC;
import static com.lsmom.biz.consultation.ConsultationSupport.num;
import static com.lsmom.biz.consultation.ConsultationSupport.time;
import static com.lsmom.biz.consultation.ConsultationSupport.yes;

/** 意见咨询 Pack 的代码 Operation：提交意见（匿名议题不记署名）、草拟总结（人工或 LLM，只能是草稿）。 */
public final class ConsultationOperations {

    private ConsultationOperations() {
    }

    /**
     * 提交意见：议题须在征求中、未过截止时间，提交人须在征求范围（所选组织及下级）内。
     * 匿名议题不写署名、创建人记为系统；提交主体只保存在受审计权限保护的字段与操作审计中。
     */
    @LsOperation(code = ConsultationPack.SUBMIT, concept = OPINION, name = "提交意见", risk = Risk.C0, permission = "ls:consultation:opine", inputSchema = """
        {"type":"object","required":["topicId","content"],"properties":{"topicId":{"type":"integer"},
          "content":{"type":"string","minLength":1,"maxLength":4000},"category":{"type":"string"}}}""")
    public static class SubmitOpinion implements OperationHandler {

        private final JdbcTemplate jdbc;
        private final ManagedRecords records;
        private final Clock clock;

        public SubmitOpinion(JdbcTemplate jdbc, ManagedRecords records, Clock clock) {
            this.jdbc = jdbc;
            this.records = records;
            this.clock = clock;
        }

        @Override
        public Object handle(OperationContext ctx) {
            long topicId = ctx.input().get("topicId").asLong();
            Map<String, Object> topic = records.find(TOPIC, topicId).orElseThrow(() -> LsBizException.notFound("议题", topicId));
            Long me = ctx.actor().effectiveParty();
            if (me == null) throw new LsBizException("NO_PARTY", "当前账号未关联人员，不能提交意见");
            if (!"OPEN".equals(topic.get("status"))) throw new LsBizException("TOPIC_NOT_OPEN", "议题不在征求中");
            LocalDateTime now = LocalDateTime.now(clock);
            if (now.isAfter(time(topic.get("deadline")))) throw new LsBizException("TOPIC_EXPIRED", "已过截止时间，不能再提交意见");
            if (!inScope(jdbc, me, num(topic.get("scopeOrg")))) throw new LsBizException("OUT_OF_SCOPE", "你不在该议题的征求范围内");
            boolean anonymous = yes(topic.get("anonymous"));
            Map<String, Object> v = new HashMap<>();
            v.put("topic", topicId);
            v.put("content", ctx.input().get("content").asText().trim());
            ConsultationEffects.category(ctx.input().path("category").asText(null), v);
            v.put("submitter", me);
            if (!anonymous) v.put("author", me);
            v.put("submittedAt", now);
            long org = num(topic.get("orgId"));
            long id = records.insert(OPINION, org, "SUBMITTED", anonymous ? null : ctx.actor().userId(), v);
            ctx.target(String.valueOf(id), 0, "SUBMITTED", org);
            ctx.eventPayload().put("topicId", topicId);
            ctx.eventPayload().put("anonymous", anonymous);
            return Map.of("opinionId", id, "anonymous", anonymous);
        }
    }

    /**
     * 草拟总结：人工或 Agent（经 LLM）按议题意见草拟归类与总结，每个观点必须引用本议题的来源意见；一律为草稿，
     * 须负责人确认（ls.consultation.Summary.publish，规则限定真人）后发布。
     */
    @LsOperation(code = ConsultationPack.DRAFT_SUMMARY, concept = SUMMARY, name = "草拟咨询总结", risk = Risk.C1, exposure = Exposure.DRAFT,
        permission = "ls:consultation:manage", inputSchema = """
        {"type":"object","required":["topicId","content","points"],"properties":{"topicId":{"type":"integer"},
          "content":{"type":"string","minLength":1,"maxLength":8000},"origin":{"enum":["MANUAL","LLM"]},
          "categories":{"type":"object"},
          "points":{"type":"array","minItems":1,"maxItems":50,"items":{"type":"object","required":["viewpoint","opinionIds"],"properties":{
            "viewpoint":{"type":"string","minLength":1,"maxLength":2000},
            "opinionIds":{"type":"array","minItems":1,"items":{"type":"integer"}}}}}}}""")
    public static class DraftSummary implements OperationHandler {

        private final ManagedRecords records;

        public DraftSummary(ManagedRecords records) {
            this.records = records;
        }

        @Override
        public Object handle(OperationContext ctx) {
            long topicId = ctx.input().get("topicId").asLong();
            Map<String, Object> topic = records.find(TOPIC, topicId).orElseThrow(() -> LsBizException.notFound("议题", topicId));
            long org = num(topic.get("orgId"));
            if (!ctx.actor().dataScope().all() && !ctx.actor().dataScope().allowsOrg(org)) {
                throw new LsBizException("ACCESS_DENIED", "议题不在你的数据范围内");
            }
            if (!"CLOSED".equals(topic.get("status"))) throw new LsBizException("TOPIC_NOT_CLOSED", "议题截止后才能生成总结");
            List<Map<String, Object>> points = new ArrayList<>();
            int seq = 0;
            for (JsonNode p : ctx.input().get("points")) {
                seq++;
                List<Long> ids = new ArrayList<>();
                p.get("opinionIds").forEach(x -> ids.add(x.asLong()));
                requireOpinionsOf(records, topicId, ids, "第 " + seq + " 个观点");
                points.add(Map.of("seq", seq, "viewpoint", p.get("viewpoint").asText().trim(), "opinionIds", join(ids)));
            }
            // LLM 草拟的归类建议只作为草稿内容，不直接改意见；负责人在答复时确认归类
            String origin = ctx.input().path("origin").asText("MANUAL");
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("topic", topicId);
            v.put("content", ctx.input().get("content").asText().trim());
            v.put("origin", origin);
            long id = records.insert(SUMMARY, org, "DRAFT", ctx.actor().userId(), v);
            records.replaceChildren(POINT, id, points);
            ctx.target(String.valueOf(id), 0, "DRAFT", org);
            ctx.eventPayload().put("topicId", topicId);
            ctx.eventPayload().put("points", points.size());
            ctx.eventPayload().put("origin", origin);
            return Map.of("summaryId", id, "status", "DRAFT");
        }
    }

    /** 观点引用的意见必须存在且属于该议题。 */
    static void requireOpinionsOf(ManagedRecords records, long topicId, List<Long> ids, String where) {
        if (ids.isEmpty()) throw LsBizException.validation(Map.of("opinionIds", where + "没有引用来源意见"));
        for (Long oid : ids) {
            Map<String, Object> o = records.find(OPINION, oid).orElse(null);
            if (o == null || num(o.get("topic")) != topicId) {
                throw LsBizException.validation(Map.of("opinionIds", where + "引用的意见 " + oid + " 不属于本议题"));
            }
        }
    }

    static List<Long> parseIds(String s) {
        Set<Long> out = new LinkedHashSet<>();
        if (s == null || s.isBlank() || "null".equals(s)) return List.of();
        for (String x : Arrays.asList(s.split(","))) if (!x.isBlank()) out.add(Long.parseLong(x.trim()));
        return List.copyOf(out);
    }

    static String join(List<Long> ids) {
        return String.join(",", new LinkedHashSet<>(ids).stream().map(String::valueOf).toList());
    }

    /** 人员是否在征求范围内：所属组织（人员-组织关系或账号部门）是范围组织本身或其下级。 */
    static boolean inScope(JdbcTemplate jdbc, long party, long scopeOrg) {
        Integer n = jdbc.queryForObject("""
            with recursive down as (
              select id from core_org_unit where id = ?
              union all
              select c.id from core_org_unit c join down on c.parent_id = down.id)
            select count(*) from down d where exists (
                select 1 from core_party_org po where po.party_id = ? and po.org_unit_id = d.id
                   and po.valid_from <= current_date and (po.valid_to is null or po.valid_to >= current_date))
              or exists (
                select 1 from core_party p join sys_user u on u.user_id = p.user_id join core_org_unit o on o.dept_id = u.dept_id
                 where p.id = ? and o.id = d.id)""", Integer.class, scopeOrg, party, party);
        return n != null && n > 0;
    }
}
