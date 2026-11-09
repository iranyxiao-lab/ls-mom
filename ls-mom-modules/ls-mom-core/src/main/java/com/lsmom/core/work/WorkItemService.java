package com.lsmom.core.work;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.OrgUnitService;
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
 * 工作项服务：创建、查询与供状态机使用的受控状态写入。
 * 创建只要求类型、标题、说明、组织与约束 Profile，不依赖任何制造主数据。
 */
@Service
public class WorkItemService {

    static final String SELECT = """
        select w.*, o.name as org_unit_name from core_work_item w join core_org_unit o on o.id = w.org_unit_id""";
    static final RowMapper<WorkItem> MAPPER = (rs, i) -> new WorkItem(rs.getLong("id"), rs.getString("code"),
        WorkType.valueOf(rs.getString("work_type")), rs.getString("title"), rs.getString("description"),
        rs.getLong("org_unit_id"), rs.getString("org_unit_name"), rs.getString("constraint_profile"),
        WorkStatus.valueOf(rs.getString("status")), rs.getInt("priority"),
        rs.getObject("planned_start", LocalDateTime.class), rs.getObject("planned_end", LocalDateTime.class),
        rs.getObject("actual_start", LocalDateTime.class), rs.getObject("actual_end", LocalDateTime.class),
        rs.getBigDecimal("progress"), rs.getString("block_reason"), rs.getString("form_code"),
        (Integer) rs.getObject("form_version"), (Integer) rs.getObject("flow_version"), (Integer) rs.getObject("schema_version"),
        rs.getString("ext_concept"), (Long) rs.getObject("ext_id"), rs.getString("source_type"), (Long) rs.getObject("source_id"),
        (Long) rs.getObject("created_by_party"), rs.getObject("created_at", LocalDateTime.class), rs.getInt("version"));

    private final JdbcTemplate jdbc;
    private final OrgUnitService orgUnits;
    private final ConstraintProfileService profiles;
    private final Clock clock;

    public WorkItemService(JdbcTemplate jdbc, OrgUnitService orgUnits, ConstraintProfileService profiles, Clock clock) {
        this.jdbc = jdbc;
        this.orgUnits = orgUnits;
        this.profiles = profiles;
        this.clock = clock;
    }

    public record CreateWorkItem(WorkType type, String title, String description, Long orgUnitId, String constraintProfile,
                                 Integer priority, LocalDateTime plannedStart, LocalDateTime plannedEnd,
                                 String extConcept, Long extId, String sourceType, Long sourceId, Long createdByParty) {

        public static CreateWorkItem of(WorkType type, String title, String description, Long orgUnitId) {
            return new CreateWorkItem(type, title, description, orgUnitId, null, null, null, null, null, null, null, null, null);
        }

        public CreateWorkItem withSource(String srcType, Long srcId) {
            return new CreateWorkItem(type, title, description, orgUnitId, constraintProfile, priority, plannedStart, plannedEnd,
                extConcept, extId, srcType, srcId, createdByParty);
        }

        public CreateWorkItem withExt(String concept, Long id) {
            return new CreateWorkItem(type, title, description, orgUnitId, constraintProfile, priority, plannedStart, plannedEnd,
                concept, id, sourceType, sourceId, createdByParty);
        }

        public CreateWorkItem withPlan(LocalDateTime start, LocalDateTime end) {
            return new CreateWorkItem(type, title, description, orgUnitId, constraintProfile, priority, start, end,
                extConcept, extId, sourceType, sourceId, createdByParty);
        }

        public CreateWorkItem createdBy(Long partyId) {
            return new CreateWorkItem(type, title, description, orgUnitId, constraintProfile, priority, plannedStart, plannedEnd,
                extConcept, extId, sourceType, sourceId, partyId);
        }
    }

    @Transactional
    public WorkItem create(CreateWorkItem cmd) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (cmd.type() == null) errors.put("workType", "工作类型必填");
        if (cmd.title() == null || cmd.title().isBlank()) errors.put("title", "title 必填");
        else if (cmd.title().length() > 200) errors.put("title", "title 不能超过 200 字");
        if (cmd.description() == null) errors.put("description", "description 必填（可为空字符串）");
        if (cmd.orgUnitId() == null) errors.put("orgUnitId", "所属组织必填");
        if (cmd.priority() != null && (cmd.priority() < 1 || cmd.priority() > 5)) errors.put("priority", "优先级为 1～5");
        if (cmd.plannedStart() != null && cmd.plannedEnd() != null && cmd.plannedEnd().isBefore(cmd.plannedStart())) {
            errors.put("plannedEnd", "计划结束不能早于计划开始");
        }
        if (!errors.isEmpty()) throw LsBizException.validation(errors);

        orgUnits.requireActive(cmd.orgUnitId(), LocalDate.now(clock));
        String profile = cmd.constraintProfile() != null
            ? profiles.get(cmd.constraintProfile()).code()
            : profiles.effectiveForOrg(cmd.orgUnitId()).code();
        String code = "WI" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE)
            + String.format("%05d", jdbc.queryForObject("select nextval('core_work_item_no_seq')", Long.class));

        Long id = jdbc.queryForObject("""
            insert into core_work_item (code, work_type, title, description, org_unit_id, constraint_profile, priority,
              planned_start, planned_end, ext_concept, ext_id, source_type, source_id, created_by_party)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
            code, cmd.type().name(), cmd.title().trim(), cmd.description(), cmd.orgUnitId(), profile,
            cmd.priority() == null ? 3 : cmd.priority(), cmd.plannedStart(), cmd.plannedEnd(),
            cmd.extConcept(), cmd.extId(), cmd.sourceType(), cmd.sourceId(), cmd.createdByParty());
        return get(id);
    }

    public WorkItem get(long id) {
        List<WorkItem> list = jdbc.query(SELECT + " where w.id = ?", MAPPER, id);
        if (list.isEmpty()) throw LsBizException.notFound("工作", id);
        return list.get(0);
    }

    public record WorkQuery(WorkType type, WorkStatus status, String keyword, Long orgUnitId, int page, int size) {
        public static WorkQuery all() {
            return new WorkQuery(null, null, null, null, 1, 50);
        }
    }

    /** 按数据域过滤的工作列表。 */
    public List<WorkItem> list(WorkQuery q, DataScope scope) {
        StringBuilder sql = new StringBuilder(SELECT).append(" where 1 = 1");
        List<Object> args = new ArrayList<>();
        appendScope(sql, args, scope);
        if (q.type() != null) { sql.append(" and w.work_type = ?"); args.add(q.type().name()); }
        if (q.status() != null) { sql.append(" and w.status = ?"); args.add(q.status().name()); }
        if (q.orgUnitId() != null) { sql.append(" and w.org_unit_id = ?"); args.add(q.orgUnitId()); }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            sql.append(" and (w.title ilike ? or w.code ilike ?)");
            String like = "%" + q.keyword().replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like); args.add(like);
        }
        int size = Math.min(Math.max(q.size(), 1), 500);
        sql.append(" order by w.id desc limit ? offset ?");
        args.add(size); args.add((long) (Math.max(q.page(), 1) - 1) * size);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 数据域条件：仅本人口径按创建人或分派给本人过滤，其余按组织集合过滤。 */
    static void appendScope(StringBuilder sql, List<Object> args, DataScope scope) {
        if (scope.all()) return;
        if (scope.level() == com.lsmom.core.org.DataScopeLevel.SELF && scope.partyId() != null) {
            sql.append(" and (w.created_by_party = ? or exists (select 1 from core_work_assignment a where a.work_item_id = w.id")
                .append(" and a.status = 'ACTIVE' and a.resource_type = 'PARTY' and a.resource_ref = ?))");
            args.add(scope.partyId()); args.add(String.valueOf(scope.partyId()));
            return;
        }
        if (scope.orgUnitIds().isEmpty()) { sql.append(" and false"); return; }
        sql.append(" and w.org_unit_id in (").append(String.join(",", scope.orgUnitIds().stream().map(x -> "?").toList())).append(")");
        args.addAll(scope.orgUnitIds());
    }

    /**
     * 受控状态写入，只供状态机执行器调用：校验当前状态与版本（乐观锁），失败抛 {@code CONCURRENT_MODIFICATION}。
     */
    @Transactional
    public WorkItem transition(long id, int expectedVersion, WorkStatus from, WorkStatus to, Map<String, Object> changes) {
        StringBuilder sql = new StringBuilder("update core_work_item set status = ?, version = version + 1, updated_at = now()");
        List<Object> args = new ArrayList<>(List.of(to.name()));
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            if (!TRANSITION_COLUMNS.contains(e.getKey())) throw new IllegalArgumentException("不可由迁移写入的列：" + e.getKey());
            sql.append(", ").append(e.getKey()).append(" = ?");
            args.add(e.getValue());
        }
        sql.append(" where id = ? and version = ? and status = ?");
        args.add(id); args.add(expectedVersion); args.add(from.name());
        if (jdbc.update(sql.toString(), args.toArray()) == 0) {
            WorkItem now = get(id);
            throw new LsBizException("CONCURRENT_MODIFICATION", "工作已被他人修改，请刷新后重试",
                Map.of("currentStatus", now.status().name(), "currentVersion", now.version()));
        }
        return get(id);
    }

    private static final java.util.Set<String> TRANSITION_COLUMNS = java.util.Set.of(
        "block_reason", "actual_start", "actual_end", "progress", "form_code", "form_version", "flow_version", "schema_version");
}
