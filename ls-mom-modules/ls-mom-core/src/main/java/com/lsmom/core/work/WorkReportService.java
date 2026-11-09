package com.lsmom.core.work;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作填报：草稿可随时保存，提交时按工作所属组织当前生效的约束 Profile 做服务端校验。
 * 草稿不参与统计；提交后计入工作进度。填报不产生库存或会计过账。
 */
@Service
public class WorkReportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<WorkReport.Consumption>> CONSUMPTION = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final WorkItemService works;
    private final ConstraintProfileService profiles;
    private final Clock clock;

    private final RowMapper<WorkReport> mapper = (rs, i) -> new WorkReport(rs.getLong("id"), rs.getLong("work_item_id"),
        (Long) rs.getObject("reporter_party"), rs.getString("status"), rs.getBigDecimal("progress"),
        (Integer) rs.getObject("labor_minutes"), rs.getBigDecimal("output_qty"), rs.getString("output_unit"),
        readConsumption(rs.getString("consumption")), rs.getString("remark"),
        rs.getObject("submitted_at", LocalDateTime.class), rs.getInt("version"));

    public WorkReportService(JdbcTemplate jdbc, WorkItemService works, ConstraintProfileService profiles, Clock clock) {
        this.jdbc = jdbc;
        this.works = works;
        this.profiles = profiles;
        this.clock = clock;
    }

    public record ReportInput(BigDecimal progress, Integer laborMinutes, BigDecimal outputQty, String outputUnit,
                              List<WorkReport.Consumption> consumption, String remark) {
    }

    /** 保存草稿（新建或更新已有草稿）。 */
    @Transactional
    public WorkReport saveDraft(long workItemId, Long reportId, Long reporterParty, ReportInput in) {
        WorkItem work = works.get(workItemId);
        if (work.status().terminal()) {
            throw new LsBizException("WORK_CLOSED", "工作已" + (work.status() == WorkStatus.DONE ? "完成" : "取消") + "，不能再填报");
        }
        validateShape(in);
        if (reportId == null) {
            Long id = jdbc.queryForObject("""
                insert into core_work_report (work_item_id, reporter_party, progress, labor_minutes, output_qty, output_unit, consumption, remark)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id""", Long.class, workItemId, reporterParty, in.progress(),
                in.laborMinutes(), in.outputQty(), blankToNull(in.outputUnit()), writeConsumption(in.consumption()), in.remark());
            return get(id);
        }
        int n = jdbc.update("""
            update core_work_report set progress = ?, labor_minutes = ?, output_qty = ?, output_unit = ?, consumption = ?::jsonb,
              remark = ?, updated_at = now(), version = version + 1
            where id = ? and work_item_id = ? and status = 'DRAFT'""", in.progress(), in.laborMinutes(), in.outputQty(),
            blankToNull(in.outputUnit()), writeConsumption(in.consumption()), in.remark(), reportId, workItemId);
        if (n == 0) throw new LsBizException("REPORT_NOT_DRAFT", "只能修改草稿状态的填报");
        return get(reportId);
    }

    /** 正式提交：服务端按约束 Profile 校验，提交后更新工作进度。 */
    @Transactional
    public WorkReport submit(long reportId) {
        WorkReport r = get(reportId);
        if (!"DRAFT".equals(r.status())) throw new LsBizException("REPORT_NOT_DRAFT", "填报已提交");
        WorkItem work = works.get(r.workItemId());
        if (work.status().terminal()) throw new LsBizException("WORK_CLOSED", "工作已结束，不能再提交填报");
        ConstraintProfile profile = profiles.effectiveForOrg(work.orgUnitId());

        Map<String, String> errors = new LinkedHashMap<>();
        if (r.progress() == null && r.laborMinutes() == null && r.outputQty() == null
            && r.consumption().isEmpty() && (r.remark() == null || r.remark().isBlank())) {
            errors.put("report", "填报内容为空");
        }
        if (profile.outputUnitRequired() && r.outputQty() != null && blankToNull(r.outputUnit()) == null) {
            errors.put("outputUnit", "当前约束 Profile「" + profile.name() + "」要求产出必须填写单位");
        }
        if (profile.materialRequired() && work.type() == WorkType.PRODUCTION && r.consumption().isEmpty()) {
            errors.put("consumption", "当前约束 Profile「" + profile.name() + "」要求生产填报记录物料");
        }
        if (!errors.isEmpty()) throw LsBizException.validation(errors);

        jdbc.update("update core_work_report set status = 'SUBMITTED', submitted_at = ?, updated_at = now(), version = version + 1 where id = ?",
            LocalDateTime.now(clock), reportId);
        if (r.progress() != null) {
            jdbc.update("update core_work_item set progress = greatest(progress, ?), updated_at = now() where id = ?",
                r.progress(), work.id());
        }
        return get(reportId);
    }

    public WorkReport get(long id) {
        List<WorkReport> list = jdbc.query("select * from core_work_report where id = ?", mapper, id);
        if (list.isEmpty()) throw LsBizException.notFound("填报", id);
        return list.get(0);
    }

    public List<WorkReport> listByWork(long workItemId, boolean includeDrafts) {
        return jdbc.query("select * from core_work_report where work_item_id = ?" + (includeDrafts ? "" : " and status = 'SUBMITTED'")
            + " order by id", mapper, workItemId);
    }

    /** 已提交填报汇总（草稿不计）。 */
    public record ReportTotals(int reports, long laborMinutes, BigDecimal outputQty) {
    }

    public ReportTotals totals(long workItemId) {
        return jdbc.queryForObject("""
            select count(*), coalesce(sum(labor_minutes), 0), coalesce(sum(output_qty), 0)
            from core_work_report where work_item_id = ? and status = 'SUBMITTED'""",
            (rs, i) -> new ReportTotals(rs.getInt(1), rs.getLong(2), rs.getBigDecimal(3)), workItemId);
    }

    private static void validateShape(ReportInput in) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (in.progress() != null && (in.progress().signum() < 0 || in.progress().compareTo(BigDecimal.valueOf(100)) > 0)) {
            errors.put("progress", "进度为 0～100");
        }
        if (in.laborMinutes() != null && in.laborMinutes() < 0) errors.put("laborMinutes", "人工时长不能为负");
        if (in.remark() != null && in.remark().length() > 2000) errors.put("remark", "说明不能超过 2000 字");
        if (!errors.isEmpty()) throw LsBizException.validation(errors);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String writeConsumption(List<WorkReport.Consumption> list) {
        try {
            return JSON.writeValueAsString(list == null ? List.of() : list);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<WorkReport.Consumption> readConsumption(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, CONSUMPTION);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
