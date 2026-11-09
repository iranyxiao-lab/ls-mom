package com.lsmom.core.work;

import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 柔性分派：人员、班组、场地、设备任意组合。同一资源时段冲突按约束 Profile 提示或阻断。
 */
@Service
public class AssignmentService {

    static final RowMapper<Assignment> MAPPER = (rs, i) -> new Assignment(rs.getLong("id"), rs.getLong("work_item_id"),
        Assignment.ResourceType.valueOf(rs.getString("resource_type")), rs.getString("resource_ref"), rs.getString("role"),
        rs.getObject("planned_start", LocalDateTime.class), rs.getObject("planned_end", LocalDateTime.class), rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final WorkItemService works;
    private final ConstraintProfileService profiles;

    public AssignmentService(JdbcTemplate jdbc, WorkItemService works, ConstraintProfileService profiles) {
        this.jdbc = jdbc;
        this.works = works;
        this.profiles = profiles;
    }

    public record AssignTarget(Assignment.ResourceType resourceType, String resourceRef, String role,
                               LocalDateTime plannedStart, LocalDateTime plannedEnd) {
        public static AssignTarget party(long partyId, LocalDateTime start, LocalDateTime end) {
            return new AssignTarget(Assignment.ResourceType.PARTY, String.valueOf(partyId), "RESPONSIBLE", start, end);
        }
    }

    public record Conflict(String resourceType, String resourceRef, Long workItemId, String workCode, String workTitle,
                           LocalDateTime plannedStart, LocalDateTime plannedEnd) {
    }

    public record AssignResult(List<Assignment> assignments, List<Conflict> conflicts) {
        public boolean hasWarnings() {
            return !conflicts.isEmpty();
        }
    }

    /** 检查冲突（不写入），供分派前预览与守卫使用。 */
    public List<Conflict> findConflicts(long workItemId, List<AssignTarget> targets) {
        WorkItem work = works.get(workItemId);
        List<Conflict> conflicts = new ArrayList<>();
        for (AssignTarget t : targets) {
            LocalDateTime start = t.plannedStart() != null ? t.plannedStart() : work.plannedStart();
            LocalDateTime end = t.plannedEnd() != null ? t.plannedEnd() : work.plannedEnd();
            if (start == null || end == null) continue;
            conflicts.addAll(jdbc.query("""
                select a.resource_type, a.resource_ref, w.id, w.code, w.title,
                       coalesce(a.planned_start, w.planned_start) s, coalesce(a.planned_end, w.planned_end) e
                from core_work_assignment a join core_work_item w on w.id = a.work_item_id
                where a.status = 'ACTIVE' and a.resource_type = ? and a.resource_ref = ? and w.id <> ?
                  and w.status not in ('DONE', 'CANCELLED')
                  and coalesce(a.planned_start, w.planned_start) < ? and coalesce(a.planned_end, w.planned_end) > ?""",
                (rs, i) -> new Conflict(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                    rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class)),
                t.resourceType().name(), t.resourceRef(), workItemId, end, start));
        }
        return conflicts;
    }

    /** 写入分派。由 assign Operation 调用（状态迁移由状态机负责）。 */
    @Transactional
    public AssignResult assign(long workItemId, List<AssignTarget> targets, Long assignedBy) {
        if (targets == null || targets.isEmpty()) {
            throw LsBizException.validation(Map.of("targets", "至少分派一个资源"));
        }
        for (AssignTarget t : targets) {
            if (t.resourceType() == null || t.resourceRef() == null || t.resourceRef().isBlank()) {
                throw LsBizException.validation(Map.of("targets", "分派资源类型与编号必填"));
            }
        }
        WorkItem work = works.get(workItemId);
        List<Conflict> conflicts = findConflicts(workItemId, targets);
        ConstraintProfile profile = profiles.get(work.constraintProfile());
        if (!conflicts.isEmpty() && profile.conflictMode() == ConstraintProfile.ConflictMode.BLOCK) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("conflicts", conflicts);
            throw new LsBizException("ASSIGNMENT_CONFLICT", "资源时间冲突：" + conflicts.get(0).workCode() + " "
                + conflicts.get(0).workTitle(), details);
        }
        for (AssignTarget t : targets) {
            jdbc.update("""
                insert into core_work_assignment (work_item_id, resource_type, resource_ref, role, planned_start, planned_end, assigned_by)
                values (?, ?, ?, ?, ?, ?, ?)""", workItemId, t.resourceType().name(), t.resourceRef(),
                t.role() == null ? "RESPONSIBLE" : t.role(), t.plannedStart(), t.plannedEnd(), assignedBy);
        }
        return new AssignResult(listActive(workItemId), conflicts);
    }

    public List<Assignment> listActive(long workItemId) {
        return jdbc.query("select * from core_work_assignment where work_item_id = ? and status = 'ACTIVE' order by id", MAPPER, workItemId);
    }

    @Transactional
    public void release(long workItemId) {
        jdbc.update("update core_work_assignment set status = 'RELEASED' where work_item_id = ? and status = 'ACTIVE'", workItemId);
    }
}
