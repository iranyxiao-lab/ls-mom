package com.lsmom.flow.notice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发通知（显示在顶栏通知抽屉）。业务 Pack 用它通知需要确认的人，例如"交接班待确认"。
 * 带动作的通知在抽屉内可直接完成；业务办结后调用 {@link #resolve} 把同一对象的待确认通知标记为已处理。
 */
public class Notices {

    /** 抽屉内的动作：调用该 Operation（版本取对象当前版本），输入为空。 */
    public record Action(String operation, String concept, String targetId, String label) {
    }

    /** 抽屉分组。 */
    public static final List<String> TODO_KINDS = List.of("ACTION", "REMIND", "URGE", "ESCALATE", "EXCEPTION", "TRANSFER", "PACK_REVIEW", "DCS_ALARM");
    public static final List<String> SYSTEM_KINDS = List.of("SYSTEM");
    public static final List<String> MENTION_KINDS = List.of("MENTION", "CC");

    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public Notices(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int notify(String kind, Collection<Long> parties, String bizType, String bizId, String title, String message, String link, Action action,
                      Long by) {
        String act = null;
        if (action != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("operation", action.operation());
            m.put("concept", action.concept());
            m.put("targetId", action.targetId());
            m.put("label", action.label());
            try {
                act = JSON.writeValueAsString(m);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        int n = 0;
        for (Long p : parties) {
            if (p == null) continue;
            n += jdbc.update("""
                insert into biz_flow_notice (kind, party_id, biz_type, biz_id, title, message, link, action, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""", kind, p, bizType, bizId, cut(title, 200), cut(message, 500), cut(link, 500), act, by);
        }
        return n;
    }

    /** 对象办结：同一对象所有人的待确认通知标记为已处理（并已读）。 */
    public int resolve(String bizType, String bizId) {
        return jdbc.update("""
            update biz_flow_notice set done_at = now(), read_at = coalesce(read_at, now())
             where biz_type = ? and biz_id = ? and kind = 'ACTION' and done_at is null""", bizType, bizId);
    }

    /** 组织及其上级中最近一个有在岗人员的组织的全部人员（通知"该组织的人"时用）。 */
    public List<Long> nearestMembers(Long orgUnitId) {
        return jdbc.queryForList("""
            with recursive up(id, depth) as (select cast(? as bigint), 0
                                             union all select o.parent_id, up.depth + 1 from core_org_unit o join up on o.id = up.id
                                              where o.parent_id is not null),
                 m as (select up.depth, po.party_id from up join core_party_org po on po.org_unit_id = up.id
                         join core_party p on p.id = po.party_id and p.status = 'ACTIVE'
                        where po.valid_to is null or po.valid_to >= current_date)
            select distinct party_id from m where depth = (select min(depth) from m)""", Long.class, orgUnitId);
    }

    private static String cut(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }
}
