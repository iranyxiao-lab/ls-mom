package com.lsmom.core.work;

import com.lsmom.core.error.LsBizException;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工作关系：成环拒绝与父子拆分。 */
class WorkRelationTest {

    private final CoreFixture f = new CoreFixture();

    private WorkItem work(CoreFixture.Plant p, String title) {
        return f.works.create(WorkItemService.CreateWorkItem.of(WorkType.IMPROVEMENT, title, "", p.teamA().id()));
    }

    @Test
    void 祖先关系成环被拒绝() {
        CoreFixture.Plant p = f.plant();
        WorkItem a = work(p, "A"), b = work(p, "B"), c = work(p, "C");
        f.relations.link(b.id(), a.id(), WorkRelationService.RelationType.PARENT); // B 是 A 的父
        f.relations.link(c.id(), b.id(), WorkRelationService.RelationType.PARENT); // C 是 B 的父
        assertThatThrownBy(() -> f.relations.link(a.id(), c.id(), WorkRelationService.RelationType.PARENT))
            .isInstanceOfSatisfying(LsBizException.class, e -> {
                assertThat(e.getCode()).isEqualTo("RELATION_CYCLE");
                assertThat(e.getMessage()).contains("循环");
                assertThat((List<Object>) e.getDetails().get("cycle")).containsExactly((Object) c.id(), b.id(), a.id());
            });
        assertThatThrownBy(() -> f.relations.link(a.id(), a.id(), WorkRelationService.RelationType.PARENT)).isInstanceOf(LsBizException.class);
        // 关联关系不检查方向
        f.relations.link(a.id(), c.id(), WorkRelationService.RelationType.RELATED);
        assertThat(f.relations.relationsOf(a.id())).hasSize(2);
    }

    @Test
    void 依赖链成环被拒绝() {
        CoreFixture.Plant p = f.plant();
        WorkItem a = work(p, "A"), b = work(p, "B");
        f.relations.link(a.id(), b.id(), WorkRelationService.RelationType.DEPENDS);
        assertThatThrownBy(() -> f.relations.link(b.id(), a.id(), WorkRelationService.RelationType.DEPENDS))
            .extracting("code").isEqualTo("RELATION_CYCLE");
    }

    @Test
    void 拆分子工作继承组织并只有一个父() {
        CoreFixture.Plant p = f.plant();
        WorkItem parent = work(p, "大修");
        List<WorkItem> kids = f.relations.split(parent.id(), List.of("拆机", "更换轴承"), null);
        assertThat(kids).allSatisfy(k -> {
            assertThat(k.orgUnitId()).isEqualTo(parent.orgUnitId());
            assertThat(k.type()).isEqualTo(WorkType.IMPROVEMENT);
        });
        assertThat(f.relations.childrenOf(parent.id())).containsExactlyElementsOf(kids.stream().map(WorkItem::id).toList());
        WorkItem other = work(p, "其他");
        assertThatThrownBy(() -> f.relations.link(other.id(), kids.get(0).id(), WorkRelationService.RelationType.PARENT))
            .extracting("code").isEqualTo("RELATION_MULTI_PARENT");
    }
}
