package com.lsmom.core.work;

import com.lsmom.core.error.LsBizException;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DOM-01：无制造主数据创建工作；最小必填集。 */
class WorkItemTest {

    private final CoreFixture f = new CoreFixture();

    @Test
    void 没有任何BOM与仓库数据时创建生产工作() {
        CoreFixture.Plant p = f.plant();
        WorkItem w = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, "装配 200 件", "按客户急单", p.teamA().id()));
        assertThat(w.status()).isEqualTo(WorkStatus.DRAFT);
        assertThat(w.constraintProfile()).isEqualTo("LOOSE");
        assertThat(w.code()).startsWith("WI");
        assertThat(w.version()).isZero();
    }

    @Test
    void 核心工作表不含制造主数据字段() {
        var cols = f.jdbc.queryForList("""
            select column_name from information_schema.columns
            where table_name in ('core_work_item','core_work_assignment','core_work_report','core_work_relation')""", String.class);
        assertThat(cols).noneMatch(c -> c.matches("(?i).*(bom|routing|recipe|work_center|warehouse|batch|serial|cost_center|stock|inventory).*"));
    }

    @Test
    void 缺少标题返回校验错误() {
        CoreFixture.Plant p = f.plant();
        assertThatThrownBy(() -> f.works.create(WorkItemService.CreateWorkItem.of(WorkType.PRODUCTION, null, "", p.teamA().id())))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("VALIDATION_FAILED");
                assertThat(e.fieldErrors()).containsKey("title");
            });
        assertThatThrownBy(() -> f.works.create(WorkItemService.CreateWorkItem.of(null, "x", "", null)))
            .isInstanceOfSatisfying(LsBizException.class, e ->
                assertThat(e.fieldErrors()).containsKeys("workType", "orgUnitId"));
    }

    @Test
    void 状态写入带乐观锁() {
        CoreFixture.Plant p = f.plant();
        WorkItem w = f.works.create(WorkItemService.CreateWorkItem.of(WorkType.OTHER, "x", "", p.teamA().id()));
        WorkItem ready = f.works.transition(w.id(), 0, WorkStatus.DRAFT, WorkStatus.READY, Map.of());
        assertThat(ready.version()).isEqualTo(1);
        assertThatThrownBy(() -> f.works.transition(w.id(), 0, WorkStatus.DRAFT, WorkStatus.READY, Map.of()))
            .extracting("code").isEqualTo("CONCURRENT_MODIFICATION");
        assertThatThrownBy(() -> f.works.transition(w.id(), 1, WorkStatus.READY, WorkStatus.ASSIGNED, Map.of("status", "DONE")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
