package com.lsmom.core.issue;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.OrgUnitService;
import com.lsmom.core.work.ConstraintProfileService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.core.work.WorkType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issue 闭环外壳：登记、来源去重、多对象关联、生成整改工作与验证规则。
 * 状态推进由状态机经命名 Operation 调用 {@link #transition}；本类提供各迁移所需的守卫判断。
 */
@Service
public class IssueService {

    static final RowMapper<Issue> MAPPER = (rs, i) -> new Issue(rs.getLong("id"), rs.getString("code"), rs.getString("issue_type"),
        rs.getString("title"), rs.getString("description"), rs.getString("severity"), IssueStatus.valueOf(rs.getString("status")),
        rs.getLong("org_unit_id"), rs.getString("constraint_profile"), rs.getString("source_type"), rs.getString("source_key"),
        rs.getInt("occurrence_count"), rs.getObject("last_occurred_at", LocalDateTime.class), (Long) rs.getObject("reporter_party"),
        (Long) rs.getObject("owner_party"), rs.getObject("due_at", LocalDateTime.class), (Long) rs.getObject("verified_by"),
        rs.getString("verify_conclusion"), rs.getObject("verified_at", LocalDateTime.class), rs.getString("ext_concept"),
        (Long) rs.getObject("ext_id"), rs.getObject("created_at", LocalDateTime.class), rs.getInt("version"));

    private final JdbcTemplate jdbc;
    private final OrgUnitService orgUnits;
    private final ConstraintProfileService profiles;
    private final WorkItemService works;
    private final Clock clock;

    public IssueService(JdbcTemplate jdbc, OrgUnitService orgUnits, ConstraintProfileService profiles, WorkItemService works, Clock clock) {
        this.jdbc = jdbc;
        this.orgUnits = orgUnits;
        this.profiles = profiles;
        this.works = works;
        this.clock = clock;
    }

    public record RaiseIssue(String type, String title, String description, String severity, Long orgUnitId,
                             String sourceType, String sourceKey, Long reporterParty, Long ownerParty, LocalDateTime dueAt,
                             String extConcept, Long extId, List<ObjectRef> links) {

        public static RaiseIssue manual(String type, String title, Long orgUnitId, Long reporter) {
            return new RaiseIssue(type, title, "", null, orgUnitId, "MANUAL", null, reporter, null, null, null, null, List.of());
        }

        public RaiseIssue withSource(String srcType, String key) {
            return new RaiseIssue(type, title, description, severity, orgUnitId, srcType, key, reporterParty, ownerParty, dueAt,
                extConcept, extId, links);
        }

        public RaiseIssue withExt(String concept, Long id) {
            return new RaiseIssue(type, title, description, severity, orgUnitId, sourceType, sourceKey, reporterParty, ownerParty,
                dueAt, concept, id, links);
        }

        public RaiseIssue withLinks(List<ObjectRef> refs) {
            return new RaiseIssue(type, title, description, severity, orgUnitId, sourceType, sourceKey, reporterParty, ownerParty,
                dueAt, extConcept, extId, refs);
        }

        public RaiseIssue ownedBy(Long party) {
            return new RaiseIssue(type, title, description, severity, orgUnitId, sourceType, sourceKey, reporterParty, party,
                dueAt, extConcept, extId, links);
        }
    }

    public record ObjectRef(String objectType, String objectId) {
    }

    public record RaiseResult(Issue issue, boolean deduplicated) {
    }

    /** 登记 Issue。带来源去重键时，同一键再次到达只累计发生次数与最近发生时间。 */
    @Transactional
    public RaiseResult raise(RaiseIssue cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd.type() == null || cmd.type().isBlank()) errors.put("type", "类型必填");
        if (cmd.title() == null || cmd.title().isBlank()) errors.put("title", "标题必填");
        if (cmd.orgUnitId() == null) errors.put("orgUnitId", "所属组织必填");
        if (cmd.severity() != null && !List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(cmd.severity())) {
            errors.put("severity", "严重度取 LOW/MEDIUM/HIGH/CRITICAL");
        }
        if (!errors.isEmpty()) throw LsBizException.validation(errors);

        LocalDateTime now = LocalDateTime.now(clock);
        if (cmd.sourceKey() != null) {
            List<Long> hit = jdbc.queryForList("""
                update core_issue set occurrence_count = occurrence_count + 1, last_occurred_at = ?, updated_at = now()
                where source_key = ? returning id""", Long.class, now, cmd.sourceKey());
            if (!hit.isEmpty()) return new RaiseResult(get(hit.get(0)), true);
        }
        orgUnits.requireActive(cmd.orgUnitId(), LocalDate.now(clock));
        String code = "IS" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
            + String.format("%05d", jdbc.queryForObject("select nextval('core_issue_no_seq')", Long.class));
        List<Long> ids = jdbc.queryForList("""
            insert into core_issue (code, issue_type, title, description, severity, org_unit_id, constraint_profile, source_type,
              source_key, last_occurred_at, reporter_party, owner_party, due_at, ext_concept, ext_id)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (source_key) where source_key is not null do nothing returning id""", Long.class,
            code, cmd.type(), cmd.title().trim(), cmd.description() == null ? "" : cmd.description(),
            cmd.severity() == null ? "MEDIUM" : cmd.severity(), cmd.orgUnitId(), profiles.effectiveForOrg(cmd.orgUnitId()).code(),
            cmd.sourceType() == null ? "MANUAL" : cmd.sourceType(), cmd.sourceKey(), now, cmd.reporterParty(), cmd.ownerParty(),
            cmd.dueAt(), cmd.extConcept(), cmd.extId());
        if (ids.isEmpty()) {
            // 并发下另一请求刚插入同一去重键：按重复处理
            return raise(cmd);
        }
        for (ObjectRef ref : cmd.links() == null ? List.<ObjectRef>of() : cmd.links()) {
            link(ids.get(0), ref, "RELATED");
        }
        return new RaiseResult(get(ids.get(0)), false);
    }

    public Issue get(long id) {
        List<Issue> list = jdbc.query("select * from core_issue where id = ?", MAPPER, id);
        if (list.isEmpty()) throw LsBizException.notFound("Issue", id);
        return list.get(0);
    }

    public Issue getByExt(String concept, long extId) {
        List<Issue> list = jdbc.query("select * from core_issue where ext_concept = ? and ext_id = ?", MAPPER, concept, extId);
        if (list.isEmpty()) throw LsBizException.notFound("Issue 扩展记录", concept + ":" + extId);
        return list.get(0);
    }

    @Transactional
    public void link(long issueId, ObjectRef ref, String role) {
        if (ref.objectType() == null || ref.objectId() == null) throw LsBizException.validation(Map.of("link", "关联对象类型与编号必填"));
        jdbc.update("insert into core_issue_link (issue_id, object_type, object_id, link_role) values (?, ?, ?, ?) on conflict do nothing",
            issueId, ref.objectType(), ref.objectId(), role);
    }

    public List<ObjectRef> links(long issueId, String role) {
        return jdbc.query("select object_type, object_id from core_issue_link where issue_id = ? and (?::varchar is null or link_role = ?::varchar) order by id",
            (rs, i) -> new ObjectRef(rs.getString(1), rs.getString(2)), issueId, role, role);
    }

    /** 生成整改工作（IMPROVEMENT），双向可追溯：工作 source 指向 Issue，Issue 关联 RECTIFICATION。 */
    @Transactional
    public List<WorkItem> createRectifications(long issueId, List<String> titles, Long createdBy) {
        Issue issue = get(issueId);
        if (issue.status() == IssueStatus.CLOSED || issue.status() == IssueStatus.CANCELLED || issue.status() == IssueStatus.REJECTED) {
            throw new LsBizException("ISSUE_CLOSED", "Issue 已结束，不能再生成整改工作");
        }
        if (titles == null || titles.isEmpty()) throw LsBizException.validation(Map.of("titles", "至少一条整改工作"));
        List<WorkItem> created = new ArrayList<>();
        for (String t : titles) {
            WorkItem w = works.create(WorkItemService.CreateWorkItem.of(WorkType.IMPROVEMENT, t, "整改：" + issue.title(), issue.orgUnitId())
                .withSource("ISSUE", issueId).createdBy(createdBy));
            link(issueId, new ObjectRef("WORK", String.valueOf(w.id())), "RECTIFICATION");
            created.add(w);
        }
        return created;
    }

    public List<WorkItem> rectifications(long issueId) {
        return links(issueId, "RECTIFICATION").stream().map(r -> works.get(Long.parseLong(r.objectId()))).toList();
    }

    /** 守卫：整改工作全部完成（取消的不计）才能提交验证。 */
    public void requireRectificationsDone(long issueId) {
        List<WorkItem> rect = rectifications(issueId);
        List<WorkItem> open = rect.stream().filter(w -> w.status() != WorkStatus.DONE && w.status() != WorkStatus.CANCELLED).toList();
        if (!open.isEmpty()) {
            throw new LsBizException("RECTIFICATION_INCOMPLETE", "还有 " + open.size() + " 条整改工作未完成",
                Map.of("unfinished", open.stream().map(w -> Map.of("id", w.id(), "code", w.code(), "title", w.title(),
                    "status", w.status().name())).toList()));
        }
    }

    /** 守卫：Profile 要求分离时，验证人不得是唯一整改执行人。 */
    public void requireVerifierSeparation(long issueId, long verifierParty) {
        Issue issue = get(issueId);
        if (!profiles.get(issue.constraintProfile()).verifySeparation()) return;
        List<String> executors = jdbc.queryForList("""
            select distinct a.resource_ref from core_issue_link l
            join core_work_assignment a on a.work_item_id = l.object_id::bigint and a.resource_type = 'PARTY' and a.role = 'RESPONSIBLE'
            where l.issue_id = ? and l.link_role = 'RECTIFICATION' and l.object_type = 'WORK'""", String.class, issueId);
        if (executors.size() == 1 && executors.get(0).equals(String.valueOf(verifierParty))) {
            throw new LsBizException("VERIFIER_NOT_SEPARATED", "验证人不能是唯一的整改执行人");
        }
    }

    /** 受控状态写入（乐观锁），只供状态机执行器调用。 */
    @Transactional
    public Issue transition(long id, int expectedVersion, IssueStatus from, IssueStatus to, Map<String, Object> changes) {
        StringBuilder sql = new StringBuilder("update core_issue set status = ?, version = version + 1, updated_at = now()");
        List<Object> args = new ArrayList<>(List.of(to.name()));
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            if (!TRANSITION_COLUMNS.contains(e.getKey())) throw new IllegalArgumentException("不可由迁移写入的列：" + e.getKey());
            sql.append(", ").append(e.getKey()).append(" = ?");
            args.add(e.getValue());
        }
        sql.append(" where id = ? and version = ? and status = ?");
        args.add(id); args.add(expectedVersion); args.add(from.name());
        if (jdbc.update(sql.toString(), args.toArray()) == 0) {
            Issue now = get(id);
            throw new LsBizException("CONCURRENT_MODIFICATION", "Issue 已被他人修改，请刷新后重试",
                Map.of("currentStatus", now.status().name(), "currentVersion", now.version()));
        }
        return get(id);
    }

    private static final java.util.Set<String> TRANSITION_COLUMNS = java.util.Set.of(
        "owner_party", "due_at", "verified_by", "verify_conclusion", "verified_at");
}
