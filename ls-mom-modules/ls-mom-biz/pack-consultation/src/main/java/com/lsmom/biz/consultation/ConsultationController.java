package com.lsmom.biz.consultation;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.meta.concept.ManagedRecords;
import org.dromara.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.lsmom.biz.consultation.ConsultationPack.AUDIT;
import static com.lsmom.biz.consultation.ConsultationPack.OPINION;
import static com.lsmom.biz.consultation.ConsultationPack.POINT;
import static com.lsmom.biz.consultation.ConsultationPack.SUMMARY;
import static com.lsmom.biz.consultation.ConsultationPack.TOPIC;
import static com.lsmom.biz.consultation.ConsultationSupport.num;
import static com.lsmom.biz.consultation.ConsultationSupport.yes;

/**
 * 意见咨询的只读查询：可参与的议题、我的意见、答复人看到的意见（匿名议题不含提交人）、总结与引用。写操作一律走 Operation。
 */
@RestController
@RequestMapping("/api/consultation")
public class ConsultationController {

    private final ActorResolver actors;
    private final JdbcTemplate jdbc;
    private final ManagedRecords records;

    public ConsultationController(ActorResolver actors, JdbcTemplate jdbc, ManagedRecords records) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.records = records;
    }

    /** 我可参与的议题（征求中或已截止，我在征求范围内），含我提交的意见数与已发布总结。 */
    @GetMapping("/my/topics")
    public R<List<Map<String, Object>>> myTopics() {
        ActorContext a = require("ls:consultation:opine");
        Long me = a.effectiveParty();
        if (me == null) return R.ok(List.of());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : topics("t.status in ('OPEN', 'CLOSED')", List.of())) {
            if (!ConsultationOperations.inScope(jdbc, me, num(t.get("scope_org")))) continue;
            t.put("my_count", jdbc.queryForObject("select count(*) from " + records.table(OPINION) + " where del_flag = '0' and "
                + col(OPINION, "topic") + " = ? and " + col(OPINION, "submitter") + " = ?", Integer.class, t.get("id"), me));
            out.add(t);
        }
        return R.ok(out);
    }

    /** 我提交的意见（含匿名议题中的），可看到答复与是否已转整改。 */
    @GetMapping("/my/opinions")
    public R<List<Map<String, Object>>> myOpinions(@RequestParam(required = false) Long topicId) {
        ActorContext a = require("ls:consultation:opine");
        if (a.effectiveParty() == null) return R.ok(List.of());
        List<Object> args = new ArrayList<>(List.of(a.effectiveParty()));
        String extra = "";
        if (topicId != null) {
            extra = " and o." + col(OPINION, "topic") + " = ?";
            args.add(topicId);
        }
        return R.ok(opinions("o." + col(OPINION, "submitter") + " = ?" + extra, args, false));
    }

    /** 答复人 / 负责人：数据域内的议题与意见统计。 */
    @GetMapping("/topics")
    public R<List<Map<String, Object>>> topics() {
        ActorContext a = requireAny("ls:consultation:reply", "ls:consultation:manage");
        List<Object> args = new ArrayList<>();
        String scope = scopeSql(a.dataScope(), "t.org_id", args);
        if (scope == null) return R.ok(List.of());
        List<Map<String, Object>> list = topics(scope, args);
        for (Map<String, Object> t : list) {
            Map<String, Object> c = jdbc.queryForMap("select count(*) as total, count(*) filter (where status = 'SUBMITTED') as pending, "
                + "count(*) filter (where status = 'CONVERTED') as converted from " + records.table(OPINION) + " where del_flag = '0' and "
                + col(OPINION, "topic") + " = ?", t.get("id"));
            t.putAll(c);
        }
        return R.ok(list);
    }

    /** 议题下的意见：匿名议题不返回提交人（具备审计权限的除外）。 */
    @GetMapping("/topics/{id}/opinions")
    public R<List<Map<String, Object>>> topicOpinions(@PathVariable long id) {
        ActorContext a = requireAny("ls:consultation:reply", "ls:consultation:manage");
        Map<String, Object> t = visibleTopic(a, id);
        boolean anonymous = yes(t.get("anonymous"));
        List<Map<String, Object>> list = opinions("o." + col(OPINION, "topic") + " = ?", List.of(id), a.hasPermission(AUDIT));
        if (anonymous && !a.hasPermission(AUDIT)) list.forEach(o -> {
            o.remove("author_id");
            o.put("author_name", null);
        });
        return R.ok(list);
    }

    /** 议题总结：已发布的对范围内员工与处理人可见；草稿只对负责人可见。观点附带引用的来源意见。 */
    @GetMapping("/topics/{id}/summary")
    public R<Map<String, Object>> summary(@PathVariable long id) {
        ActorContext a = actors.current();
        Map<String, Object> t = records.find(TOPIC, id).orElseThrow(() -> LsBizException.notFound("议题", id));
        boolean handler = (a.hasPermission("ls:consultation:manage") || a.hasPermission("ls:consultation:reply")) && inScope(a, t.get("orgId"));
        boolean member = a.hasPermission("ls:consultation:opine") && a.effectiveParty() != null
            && ConsultationOperations.inScope(jdbc, a.effectiveParty(), num(t.get("scopeOrg")));
        if (!handler && !member) throw LsBizException.notFound("议题", id);
        List<Map<String, Object>> all = new ArrayList<>(records.where(SUMMARY, "topic", id));
        all.removeIf(s -> "DISCARDED".equals(s.get("status")) || (!"PUBLISHED".equals(s.get("status")) && !(handler && a.hasPermission("ls:consultation:manage"))));
        if (all.isEmpty()) return R.ok(null);
        all.sort((x, y) -> Long.compare(num(y.get("id")), num(x.get("id"))));
        Map<String, Object> s = all.stream().filter(x -> "PUBLISHED".equals(x.get("status"))).findFirst().orElse(all.get(0));
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.put("topicTitle", t.get("title"));
        out.put("confirmedByName", s.get("confirmedBy") == null ? null : partyName(num(s.get("confirmedBy"))));
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map<String, Object> p : records.children(POINT, num(s.get("id")))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", p.get("seq"));
            m.put("viewpoint", p.get("viewpoint"));
            List<Map<String, Object>> cites = new ArrayList<>();
            for (Long oid : ConsultationOperations.parseIds(String.valueOf(p.get("opinionIds")))) {
                records.find(OPINION, oid).ifPresent(o -> cites.add(Map.of("id", oid, "content", o.get("content"),
                    "category", o.get("category") == null ? "" : o.get("category"))));
            }
            m.put("opinions", cites);
            points.add(m);
        }
        out.put("points", points);
        return R.ok(out);
    }

    private List<Map<String, Object>> topics(String where, List<Object> args) {
        String t = records.table(TOPIC);
        return new ArrayList<>(jdbc.queryForList("""
            select t.id, t.version, t.status, t.org_id, t.%s as title, t.%s as description, t.%s as scope_org, so.name as scope_name,
                   t.%s as deadline, coalesce(t.%s, false) as anonymous, t.%s as owner, op.name as owner_name,
                   (select s.id from %s s where s.del_flag = '0' and s.status = 'PUBLISHED' and s.%s = t.id order by s.id desc limit 1) as summary_id
              from %s t left join core_org_unit so on so.id = t.%s left join core_party op on op.id = t.%s
             where t.del_flag = '0' and %s order by t.%s desc, t.id desc""".formatted(
            col(TOPIC, "title"), col(TOPIC, "description"), col(TOPIC, "scopeOrg"), col(TOPIC, "deadline"), col(TOPIC, "anonymous"),
            col(TOPIC, "owner"), records.table(SUMMARY), col(SUMMARY, "topic"), t, col(TOPIC, "scopeOrg"), col(TOPIC, "owner"), where,
            col(TOPIC, "deadline")), args.toArray()));
    }

    private List<Map<String, Object>> opinions(String where, List<Object> args, boolean withSubmitter) {
        String sql = """
            select o.id, o.version, o.status, o.%s as topic_id, t.%s as topic_title, coalesce(t.%s, false) as anonymous, o.%s as content,
                   o.%s as category, o.%s as author_id, pa.name as author_name, o.%s as submitted_at, o.%s as reply, o.%s as replied_at,
                   o.%s as issue_id, i.code as issue_code, i.status as issue_status%s
              from %s o join %s t on t.id = o.%s left join core_party pa on pa.id = o.%s left join core_issue i on i.id = o.%s%s
             where o.del_flag = '0' and %s order by o.%s desc, o.id desc""".formatted(
            col(OPINION, "topic"), col(TOPIC, "title"), col(TOPIC, "anonymous"), col(OPINION, "content"), col(OPINION, "category"),
            col(OPINION, "author"), col(OPINION, "submittedAt"), col(OPINION, "reply"), col(OPINION, "repliedAt"), col(OPINION, "issue"),
            withSubmitter ? ", ps.name as submitter_name" : "", records.table(OPINION), records.table(TOPIC), col(OPINION, "topic"),
            col(OPINION, "author"), col(OPINION, "issue"),
            withSubmitter ? " left join core_party ps on ps.id = o." + col(OPINION, "submitter") : "", where, col(OPINION, "submittedAt"));
        return new ArrayList<>(jdbc.queryForList(sql, args.toArray()));
    }

    private Map<String, Object> visibleTopic(ActorContext a, long id) {
        Map<String, Object> t = records.find(TOPIC, id).orElseThrow(() -> LsBizException.notFound("议题", id));
        if (!inScope(a, t.get("orgId"))) throw LsBizException.notFound("议题", id);
        return t;
    }

    private String partyName(long id) {
        return jdbc.queryForList("select name from core_party where id = ?", String.class, id).stream().findFirst().orElse("");
    }

    private String col(String concept, String field) {
        return records.column(concept, field);
    }

    private ActorContext require(String perm) {
        ActorContext a = actors.current();
        if (!a.hasPermission(perm)) throw new LsBizException("FORBIDDEN", "没有权限：" + perm);
        return a;
    }

    private ActorContext requireAny(String... perms) {
        ActorContext a = actors.current();
        for (String p : perms) if (a.hasPermission(p)) return a;
        throw new LsBizException("FORBIDDEN", "没有权限：" + String.join(" / ", perms));
    }

    private static boolean inScope(ActorContext a, Object org) {
        DataScope s = a.dataScope();
        return s.all() || (org != null && s.orgUnitIds().contains(((Number) org).longValue()));
    }

    /** 数据域条件；数据域为空时返回 null（无可见数据）。 */
    private static String scopeSql(DataScope s, String column, List<Object> args) {
        if (s.all()) return "true";
        if (s.orgUnitIds().isEmpty()) return null;
        args.addAll(s.orgUnitIds());
        return column + " in (" + String.join(",", s.orgUnitIds().stream().map(x -> "?").toList()) + ")";
    }
}
