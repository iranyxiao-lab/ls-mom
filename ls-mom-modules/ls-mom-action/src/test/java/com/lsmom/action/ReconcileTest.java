package com.lsmom.action;

import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.action.registry.OperationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5.3：@LsOperation 与 meta_operation 对账，不一致启动失败。 */
class ReconcileTest {

    private final ActionFixture f = new ActionFixture();

    @LsOperation(code = "ls.core.Work.probeV1", concept = "ls.core.Work", name = "探针", risk = Risk.C1, permission = "a")
    static class ProbeV1 implements OperationHandler {
        public Object handle(OperationContext ctx) { return null; }
    }

    @LsOperation(code = "ls.core.Work.probeV1", concept = "ls.core.Work", name = "探针", risk = Risk.C2, permission = "a")
    static class ProbeChangedSameVersion implements OperationHandler {
        public Object handle(OperationContext ctx) { return null; }
    }

    @LsOperation(code = "ls.core.Work.probeV1", concept = "ls.core.Work", name = "探针", risk = Risk.C2, permission = "a", version = 2)
    static class ProbeV2 implements OperationHandler {
        public Object handle(OperationContext ctx) { return null; }
    }

    @LsOperation(code = "ls.core.Work.probeBadConcept", concept = "ls.nowhere.Thing", name = "坏", risk = Risk.C1)
    static class BadConcept implements OperationHandler {
        public Object handle(OperationContext ctx) { return null; }
    }

    private List<OperationHandler> with(OperationHandler h) {
        List<OperationHandler> l = new ArrayList<>(f.handlers());
        l.add(h);
        return l;
    }

    @Test
    void 契约不一致启动失败_提升版本可升级_孤儿元数据失败() {
        try {
            f.operations.reconcile(with(new ProbeV1()));
            assertThat(f.operations.require("ls.core.Work.probeV1").risk()).isEqualTo(Risk.C1);

            assertThatThrownBy(() -> f.operations.reconcile(with(new ProbeChangedSameVersion())))
                .isInstanceOf(OperationRegistry.OperationContractMismatch.class)
                .hasMessageContaining("ls.core.Work.probeV1").hasMessageContaining("risk C1≠C2");

            f.operations.reconcile(with(new ProbeV2()));
            assertThat(f.operations.require("ls.core.Work.probeV1").risk()).isEqualTo(Risk.C2);

            // 代码删除但元数据仍发布
            assertThatThrownBy(() -> f.operations.reconcile(f.handlers())).hasMessageContaining("没有对应处理器");
        } finally {
            f.operations.deprecate("ls.core.Work.probeV1");
        }
        f.operations.reconcile(f.handlers());
        assertThatThrownBy(() -> f.operations.reconcile(with(new BadConcept()))).hasMessageContaining("Concept 未注册");
    }

    @Test
    void 状态机迁移不能占用代码Operation编码() {
        assertThat(f.operations.find("ls.core.Work.create").orElseThrow().source()).isEqualTo("CODE");
        assertThat(f.operations.find("ls.core.Work.assign").orElseThrow().source()).isEqualTo("META");
        assertThat(f.operations.find("ls.core.Work.assign").orElseThrow().kind().name()).isEqualTo("TRANSITION");
    }
}
