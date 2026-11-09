package com.lsmom.core.work;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DOM-02：约束 Profile；柔性分派冲突提示/阻断；草稿到提交。 */
class ConstraintProfileTest {

    private final CoreFixture f = new CoreFixture();
    private static final LocalDateTime T8 = LocalDateTime.of(2030, 3, 1, 8, 0);

    private WorkReportService.ReportInput output(String unit) {
        return new WorkReportService.ReportInput(BigDecimal.valueOf(50), 120, BigDecimal.valueOf(100), unit, List.of(), "");
    }

    @Test
    void 启用严格Profile后产出必须带单位且历史不受影响() {
        CoreFixture.Plant p = f.plant();
        WorkItem before = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "启用前", "", p.teamA().id()));
        WorkReport oldReport = f.reports.submit(f.reports.saveDraft(before.id(), null, null, output(null)).id());
        f.works.transition(before.id(), 0, WorkStatus.DRAFT, WorkStatus.DONE, Map.of());

        f.profiles.save(new ConstraintProfile("UNIT_ONLY", "产出带单位", ConstraintProfile.ConflictMode.WARN, true, false, false));
        f.profiles.enableForOrg(p.workshop().id(), "UNIT_ONLY");
        assertThat(f.profiles.effectiveForOrg(p.teamA().id()).code()).isEqualTo("UNIT_ONLY");

        // 启用前创建、仍在进行中的工作，之后提交的填报也要带单位
        WorkItem running = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "进行中", "", p.teamA().id()));
        f.profiles.enableForOrg(p.workshop().id(), null);
        WorkItem running2 = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "进行中2", "", p.teamA().id()));
        f.profiles.enableForOrg(p.workshop().id(), "UNIT_ONLY");
        assertThat(running2.constraintProfile()).isEqualTo("LOOSE");
        WorkReport draft = f.reports.saveDraft(running2.id(), null, null, output(" "));
        assertThatThrownBy(() -> f.reports.submit(draft.id())).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.fieldErrors()).containsKey("outputUnit"));
        assertThat(f.reports.submit(f.reports.saveDraft(running.id(), null, null, output("件")).id()).status()).isEqualTo("SUBMITTED");

        // 启用前已完成的工作仍可查看与统计
        assertThat(f.reports.get(oldReport.id()).outputUnit()).isNull();
        assertThat(f.reports.totals(before.id()).outputQty()).isEqualByComparingTo("100");
        assertThat(f.works.get(before.id()).status()).isEqualTo(WorkStatus.DONE);
    }

    @Test
    void 草稿不参与统计提交后计入进度() {
        CoreFixture.Plant p = f.plant();
        WorkItem w = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "移动填报", "", p.teamA().id()));
        WorkReport draft = f.reports.saveDraft(w.id(), null, null, output("件"));
        draft = f.reports.saveDraft(w.id(), draft.id(), null,
            new WorkReportService.ReportInput(BigDecimal.valueOf(60), 90, BigDecimal.valueOf(120), "件",
                List.of(new WorkReport.Consumption("螺丝", BigDecimal.TEN, "个")), "补充"));
        assertThat(f.reports.totals(w.id()).reports()).isZero();
        assertThat(f.works.get(w.id()).progress()).isEqualByComparingTo("0");

        f.reports.submit(draft.id());
        assertThat(f.reports.totals(w.id())).isEqualTo(new WorkReportService.ReportTotals(1, 90, new BigDecimal("120.0000")));
        assertThat(f.works.get(w.id()).progress()).isEqualByComparingTo("60");
        assertThat(f.reports.get(draft.id()).consumption()).extracting(WorkReport.Consumption::name).containsExactly("螺丝");
        final long submittedId = draft.id();
        assertThatThrownBy(() -> f.reports.saveDraft(w.id(), submittedId, null, output("件"))).extracting("code").isEqualTo("REPORT_NOT_DRAFT");
    }

    @Test
    void 宽松Profile冲突只提示() {
        CoreFixture.Plant p = f.plant();
        Party worker = f.employee("张师傅", p.teamA());
        WorkItem a = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "A", "", p.teamA().id()).withPlan(T8, T8.plusHours(4)));
        WorkItem b = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "B", "", p.teamA().id()).withPlan(T8.plusHours(2), T8.plusHours(6)));
        f.assignments.assign(a.id(), List.of(AssignmentService.AssignTarget.party(worker.id(), null, null)), null);

        AssignmentService.AssignResult r = f.assignments.assign(b.id(), List.of(AssignmentService.AssignTarget.party(worker.id(), null, null)), null);
        assertThat(r.hasWarnings()).isTrue();
        assertThat(r.conflicts()).extracting(AssignmentService.Conflict::workItemId).containsExactly(a.id());
        assertThat(r.assignments()).hasSize(1);
    }

    @Test
    void 阻断Profile拒绝冲突分派并说明冲突工作() {
        CoreFixture.Plant p = f.plant();
        f.profiles.enableForOrg(p.teamA().id(), "STRICT");
        Party worker = f.employee("李师傅", p.teamA());
        WorkItem a = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "A", "", p.teamA().id()).withPlan(T8, T8.plusHours(4)));
        WorkItem b = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "B", "", p.teamA().id()).withPlan(T8.plusHours(3), T8.plusHours(5)));
        WorkItem c = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "C", "", p.teamA().id()).withPlan(T8.plusHours(4), T8.plusHours(5)));
        f.assignments.assign(a.id(), List.of(AssignmentService.AssignTarget.party(worker.id(), null, null)), null);

        assertThatThrownBy(() -> f.assignments.assign(b.id(), List.of(AssignmentService.AssignTarget.party(worker.id(), null, null)), null))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("ASSIGNMENT_CONFLICT");
                assertThat(e.getMessage()).contains(a.code());
            });
        assertThat(f.assignments.listActive(b.id())).isEmpty();
        // 首尾相接不算冲突
        assertThat(f.assignments.assign(c.id(), List.of(AssignmentService.AssignTarget.party(worker.id(), null, null)), null).hasWarnings()).isFalse();
        // 另一组合：班组与设备同时分派
        assertThat(f.assignments.assign(b.id(), List.of(
            new AssignmentService.AssignTarget(Assignment.ResourceType.TEAM, String.valueOf(p.teamA().id()), "RESPONSIBLE", null, null),
            new AssignmentService.AssignTarget(Assignment.ResourceType.EQUIPMENT, "EQ-001", "ASSIST", null, null)), null).assignments()).hasSize(2);
    }
}
