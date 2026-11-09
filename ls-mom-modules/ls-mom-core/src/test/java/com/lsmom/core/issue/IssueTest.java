package com.lsmom.core.issue;

import com.lsmom.core.error.LsBizException;
import com.lsmom.core.party.Party;
import com.lsmom.core.work.AssignmentService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.test.CoreFixture;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** spec core/issue-management 全部场景（状态推进在此直接调用受控写入，5.4 起改由 Operation 驱动）。 */
class IssueTest {

    private final CoreFixture f = new CoreFixture();
    private final IssueService issues = new IssueService(f.jdbc, f.orgUnits, f.profiles, f.works, f.clock);

    private void done(WorkItem w) {
        f.works.transition(w.id(), f.works.get(w.id()).version(), WorkStatus.DRAFT, WorkStatus.DONE, Map.of());
    }

    private Issue toRectifying(Issue i) {
        i = issues.transition(i.id(), i.version(), IssueStatus.DRAFT, IssueStatus.OPEN, Map.of());
        return issues.transition(i.id(), i.version(), IssueStatus.OPEN, IssueStatus.IN_RECTIFICATION, Map.of());
    }

    @Test
    void 独立异常不关联任何对象() {
        CoreFixture.Plant p = f.plant();
        IssueService.RaiseResult r = issues.raise(IssueService.RaiseIssue.manual("ABNORMAL", "地面漏油", p.teamA().id(), null));
        assertThat(r.deduplicated()).isFalse();
        assertThat(r.issue().status()).isEqualTo(IssueStatus.DRAFT);
        assertThat(issues.links(r.issue().id(), null)).isEmpty();
        assertThat(toRectifying(r.issue()).status()).isEqualTo(IssueStatus.IN_RECTIFICATION);
    }

    @Test
    void 可关联多个对象() {
        CoreFixture.Plant p = f.plant();
        Issue i = issues.raise(IssueService.RaiseIssue.manual("ABNORMAL", "压力异常", p.teamA().id(), null)
            .withLinks(List.of(new IssueService.ObjectRef("ASSET", "EQ-01"), new IssueService.ObjectRef("OBSERVATION", "9")))).issue();
        assertThat(issues.links(i.id(), "RELATED")).extracting(IssueService.ObjectRef::objectType).containsExactly("ASSET", "OBSERVATION");
    }

    @Test
    void 整改未完成时拒绝提交验证并列出未完成工作() {
        CoreFixture.Plant p = f.plant();
        Issue i = toRectifying(issues.raise(IssueService.RaiseIssue.manual("DEFECT", "焊缝开裂", p.teamA().id(), null)).issue());
        List<WorkItem> rect = issues.createRectifications(i.id(), List.of("补焊", "复检"), null);
        assertThat(rect).allSatisfy(w -> {
            assertThat(w.sourceType()).isEqualTo("ISSUE");
            assertThat(w.sourceId()).isEqualTo(i.id());
        });
        done(rect.get(0));

        assertThatThrownBy(() -> issues.requireRectificationsDone(i.id())).isInstanceOfSatisfying(LsBizException.class, e -> {
            assertThat(e.getCode()).isEqualTo("RECTIFICATION_INCOMPLETE");
            assertThat((List<Object>) e.getDetails().get("unfinished")).hasSize(1).first().asString().contains(rect.get(1).code());
        });
        done(rect.get(1));
        issues.requireRectificationsDone(i.id());
    }

    @Test
    void 正常关闭记录验证人与结论() {
        CoreFixture.Plant p = f.plant();
        Party fixer = f.employee("整改人", p.teamA());
        Party verifier = f.employee("验证人", p.teamA());
        Issue i = toRectifying(issues.raise(IssueService.RaiseIssue.manual("DEFECT", "漏气", p.teamA().id(), null).ownedBy(fixer.id())).issue());
        WorkItem w = issues.createRectifications(i.id(), List.of("更换密封圈"), null).get(0);
        f.assignments.assign(w.id(), List.of(AssignmentService.AssignTarget.party(fixer.id(), null, null)), null);
        done(w);

        issues.requireRectificationsDone(i.id());
        i = issues.transition(i.id(), issues.get(i.id()).version(), IssueStatus.IN_RECTIFICATION, IssueStatus.PENDING_VERIFY, Map.of());
        issues.requireVerifierSeparation(i.id(), verifier.id());
        i = issues.transition(i.id(), i.version(), IssueStatus.PENDING_VERIFY, IssueStatus.CLOSED,
            Map.of("verified_by", verifier.id(), "verify_conclusion", "保压 30 分钟无泄漏", "verified_at", LocalDateTime.now()));
        assertThat(i.status()).isEqualTo(IssueStatus.CLOSED);
        assertThat(i.verifiedBy()).isEqualTo(verifier.id());
        assertThat(i.verifyConclusion()).contains("无泄漏");
    }

    @Test
    void 验证不通过回到整改中() {
        CoreFixture.Plant p = f.plant();
        Party owner = f.employee("整改负责人", p.teamA());
        Issue i = toRectifying(issues.raise(IssueService.RaiseIssue.manual("DEFECT", "异响", p.teamA().id(), null).ownedBy(owner.id())).issue());
        i = issues.transition(i.id(), i.version(), IssueStatus.IN_RECTIFICATION, IssueStatus.PENDING_VERIFY, Map.of());
        i = issues.transition(i.id(), i.version(), IssueStatus.PENDING_VERIFY, IssueStatus.IN_RECTIFICATION,
            Map.of("verify_conclusion", "仍有异响"));
        assertThat(i.status()).isEqualTo(IssueStatus.IN_RECTIFICATION);
        assertThat(i.ownerParty()).isEqualTo(owner.id());
    }

    @Test
    void 严格Profile下验证人不能是唯一整改执行人() {
        CoreFixture.Plant p = f.plant();
        f.profiles.enableForOrg(p.teamB().id(), "STRICT");
        Party fixer = f.employee("唯一执行人", p.teamB());
        Issue i = toRectifying(issues.raise(IssueService.RaiseIssue.manual("DEFECT", "x", p.teamB().id(), null)).issue());
        WorkItem w = issues.createRectifications(i.id(), List.of("修"), null).get(0);
        f.assignments.assign(w.id(), List.of(AssignmentService.AssignTarget.party(fixer.id(), null, null)), null);
        assertThatThrownBy(() -> issues.requireVerifierSeparation(i.id(), fixer.id())).extracting("code").isEqualTo("VERIFIER_NOT_SEPARATED");
        // 宽松 Profile 不要求分离
        Issue loose = toRectifying(issues.raise(IssueService.RaiseIssue.manual("DEFECT", "y", p.teamA().id(), null)).issue());
        WorkItem w2 = issues.createRectifications(loose.id(), List.of("修"), null).get(0);
        f.assignments.assign(w2.id(), List.of(AssignmentService.AssignTarget.party(fixer.id(), null, null)), null);
        issues.requireVerifierSeparation(loose.id(), fixer.id());
    }

    @Test
    void 不符合项外壳与扩展记录一一关联() {
        CoreFixture.Plant p = f.plant();
        long extId = System.nanoTime();
        Issue i = issues.raise(IssueService.RaiseIssue.manual("NONCONFORMITY", "尺寸超差", p.teamA().id(), null)
            .withExt("ls.quality.Nonconformity", extId)).issue();
        assertThat(issues.getByExt("ls.quality.Nonconformity", extId).id()).isEqualTo(i.id());
        assertThatThrownBy(() -> issues.raise(IssueService.RaiseIssue.manual("NONCONFORMITY", "重复", p.teamA().id(), null)
            .withExt("ls.quality.Nonconformity", extId))).hasMessageContaining("uk_core_issue_ext");
    }

    @Test
    void 重复报警只累计发生次数() {
        CoreFixture.Plant p = f.plant();
        String key = PgTestSupport.uniq("DCS:TI-101:HIGH:");
        Issue first = issues.raise(IssueService.RaiseIssue.manual("DCS_ALARM", "温度高报", p.teamA().id(), null).withSource("DCS", key)).issue();
        IssueService.RaiseResult again = issues.raise(IssueService.RaiseIssue.manual("DCS_ALARM", "温度高报", p.teamA().id(), null).withSource("DCS", key));
        assertThat(again.deduplicated()).isTrue();
        assertThat(again.issue().id()).isEqualTo(first.id());
        assertThat(again.issue().occurrenceCount()).isEqualTo(2);
        assertThat(again.issue().lastOccurredAt()).isAfterOrEqualTo(first.lastOccurredAt());
        assertThat(f.jdbc.queryForObject("select count(*) from core_issue where source_key = ?", Integer.class, key)).isEqualTo(1);
    }
}
