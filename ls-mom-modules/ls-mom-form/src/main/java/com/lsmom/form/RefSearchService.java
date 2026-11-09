package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
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
        String err = FormRecordValidator.appendFilters(target, ref.path("filter"), model == null ? Map.of() : model, context, sql, args);
        if (err != null) return List.of();
        scope(actor.dataScope(), target, sql, args);
        if (ids != null && !ids.isEmpty()) {
            sql.append(" and t.id in (").append(String.join(", ", Collections.nCopies(ids.size(), "?"))).append(")");
            args.addAll(ids);
        } else if (keyword != null && !keyword.isBlank()) {
            sql.append(" and t.\"").append(label).append("\"::text ilike ?");
            args.add("%" + RefQuery.escapeLike(keyword.trim()) + "%");
        }
        sql.append(" order by t.id desc limit 20");
        return jdbc.query(sql.toString(), (rs, i) -> new Option(rs.getLong(1), rs.getString(2)), args.toArray());
    }

    private static void scope(DataScope scope, ConceptDef target, StringBuilder sql, List<Object> args) {
        if (scope == null || scope.all()) return;
        String orgCol = RefQuery.managed(target) ? "org_id" : target.field("orgUnitId") != null ? "org_unit_id" : null;
        if (orgCol == null) return;
        List<String> or = new ArrayList<>();
        if (scope.level() != DataScopeLevel.SELF && !scope.orgUnitIds().isEmpty()) {
            or.add("t.\"" + orgCol + "\" in (" + String.join(", ", Collections.nCopies(scope.orgUnitIds().size(), "?")) + ")");
            args.addAll(scope.orgUnitIds());
        }
        if (scope.partyId() != null && RefQuery.managed(target)) {
            or.add("t.created_by = ?");
            args.add(scope.partyId());
        }
        sql.append(" and ").append(or.isEmpty() ? "1 = 0" : "(" + String.join(" or ", or) + ")");
    }
}
