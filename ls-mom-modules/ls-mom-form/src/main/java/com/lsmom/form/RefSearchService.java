package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.party.PartyService;
import com.lsmom.crud.GenericCrudService;
import com.lsmom.meta.concept.ConceptDef;
import com.lsmom.meta.concept.ConceptRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引用控件远程搜索：按字段的 ref 配置（目标 Concept、过滤、显示字段）检索，过滤与提交校验同一套 SQL 片段；
 * 受控台账需要列表权限并受数据域限制；核心工作项按组织数据域；人员、组织为登录可见的目录。
 */
@Service
public class RefSearchService {

    private com.lsmom.crud.ReferenceScopes referenceScopes;

    /** 选用引用时的数据范围（共享引用）；可选注入，未注入时与读取范围相同。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferenceScopes(com.lsmom.crud.ReferenceScopes r) {
        this.referenceScopes = r;
    }

    private final ConceptRegistry concepts;
    private final JdbcTemplate jdbc;
    private final PartyService parties;

    public RefSearchService(ConceptRegistry concepts, JdbcTemplate jdbc, PartyService parties) {
        this.concepts = concepts;
        this.jdbc = jdbc;
        this.parties = parties;
    }

    public record Option(long id, String label) {
    }

    public List<Option> search(ActorContext actor, JsonNode ref, String keyword, List<Long> ids, Long orgId, Map<String, Object> model) {
        ConceptDef target = concepts.get(ref.path("concept").asText());
        if (RefQuery.managed(target) && !actor.hasPermission(GenericCrudService.permission(target.code(), "list"))) {
            throw new LsBizException("ACCESS_DENIED", "没有查看「" + target.displayName() + "」的权限");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        Long org = orgId != null ? orgId : actor.effectiveParty() == null ? null : parties.primaryOrgUnitId(actor.effectiveParty()).orElse(null);
        context.put("orgId", org);
        context.put("deptId", org);
        context.put("userId", actor.userId());
        context.put("partyId", actor.effectiveParty());
        String label = RefQuery.labelColumn(target, ref.path("labelField").asText(null));
        StringBuilder sql = new StringBuilder("select t.id, t.\"" + label + "\"::text as label from " + RefQuery.table(target) + " t where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (RefQuery.managed(target)) sql.append(" and t.del_flag = '0'");
        boolean byIds = ids != null && !ids.isEmpty();
        // 按编号回显已有取值时不套表单过滤（如记录里的设备后来报废，仍显示名称；能否继续使用由提交校验说明），数据范围照常
        if (!byIds) {
            String err = FormRecordValidator.appendFilters(target, ref.path("filter"), model == null ? Map.of() : model, context, sql, args);
            if (err != null) return List.of();
        }
        RefQuery.scope(com.lsmom.crud.ReferenceScopes.same(referenceScopes, actor.dataScope(), target.code()), target, sql, args);
        if (byIds) {
            sql.append(" and t.id in (").append(String.join(", ", Collections.nCopies(ids.size(), "?"))).append(")");
            args.addAll(ids);
        } else if (keyword != null && !keyword.isBlank()) {
            sql.append(" and t.\"").append(label).append("\"::text ilike ?");
            args.add("%" + RefQuery.escapeLike(keyword.trim()) + "%");
        }
        sql.append(" order by t.id desc limit 20");
        return jdbc.query(sql.toString(), (rs, i) -> new Option(rs.getLong(1), rs.getString(2)), args.toArray());
    }

    /**
     * 扫码选中对象：按编码精确匹配目标 Concept 的唯一文本字段（如设备编码）或显示字段，过滤与数据域同搜索一致。
     * 找不到（不存在或无权使用）、匹配到多个时拒绝。
     */
    public Option scan(ActorContext actor, JsonNode ref, String code, Long orgId, Map<String, Object> model) {
        if (code == null || code.isBlank() || code.length() > 200) throw LsBizException.validation(Map.of("code", "编码不能为空"));
        ConceptDef target = concepts.get(ref.path("concept").asText());
        if (RefQuery.managed(target) && !actor.hasPermission(GenericCrudService.permission(target.code(), "list"))) {
            throw new LsBizException("ACCESS_DENIED", "没有查看「" + target.displayName() + "」的权限");
        }
        Map<String, Object> context = new LinkedHashMap<>();
        Long org = orgId != null ? orgId : actor.effectiveParty() == null ? null : parties.primaryOrgUnitId(actor.effectiveParty()).orElse(null);
        context.put("orgId", org);
        context.put("deptId", org);
        context.put("userId", actor.userId());
        context.put("partyId", actor.effectiveParty());
        String label = RefQuery.labelColumn(target, ref.path("labelField").asText(null));
        List<String> cols = new ArrayList<>();
        for (com.lsmom.meta.concept.FieldDef f : target.fields()) {
            if (!f.ext() && f.flag("unique") && f.dataType() == com.lsmom.meta.concept.DataType.STRING) cols.add(com.lsmom.meta.concept.MetaNames.requireIdentifier(f.columnName()));
        }
        if (!cols.contains(label)) cols.add(label);
        StringBuilder sql = new StringBuilder("select t.id, t.\"" + label + "\"::text as label from " + RefQuery.table(target) + " t where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (RefQuery.managed(target)) sql.append(" and t.del_flag = '0'");
        String err = FormRecordValidator.appendFilters(target, ref.path("filter"), model == null ? Map.of() : model, context, sql, args);
        if (err == null) {
            RefQuery.scope(com.lsmom.crud.ReferenceScopes.same(referenceScopes, actor.dataScope(), target.code()), target, sql, args);
            sql.append(" and (").append(String.join(" or ", cols.stream().map(c -> "t.\"" + c + "\"::text = ?").toList())).append(")");
            for (int i = 0; i < cols.size(); i++) args.add(code.trim());
            sql.append(" limit 2");
        }
        List<Option> found = err != null ? List.of() : jdbc.query(sql.toString(), (rs, i) -> new Option(rs.getLong(1), rs.getString(2)), args.toArray());
        if (found.isEmpty()) {
            throw new LsBizException("NOT_FOUND", "没有找到编码为「" + code.trim() + "」的" + target.displayName() + "，或你无权使用", Map.of("code", code.trim()));
        }
        if (found.size() > 1) throw new LsBizException("SCAN_AMBIGUOUS", "编码「" + code.trim() + "」对应多个" + target.displayName() + "，请手动选择");
        return found.get(0);
    }
}
