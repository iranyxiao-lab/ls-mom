package com.lsmom.action;

import com.lsmom.action.api.IdempotencyPolicy;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.test.CoreFixture;

import java.util.List;
import java.util.Map;

/** 仅测试使用的 Operation（所有测试 fixture 都注册同一批，保证启动对账一致）。 */
public final class TestOperations {

    private TestOperations() {
    }

    static List<OperationHandler> all(CoreFixture core) {
        return List.of(new WriteThenFail(core), new RequiresKey());
    }

    /** 先写业务数据再失败，用于验证整体回滚。 */
    @LsOperation(code = "ls.core.Work.testWriteThenFail", concept = "ls.core.Work", name = "测试：写入后失败", risk = Risk.C1)
    public static class WriteThenFail implements OperationHandler {
        private final CoreFixture core;

        WriteThenFail(CoreFixture core) {
            this.core = core;
        }

        @Override
        public Object handle(OperationContext ctx) {
            core.jdbc.update("update core_work_item set title = 'should-rollback' where id = ?", ctx.targetIdAsLong());
            ctx.emit("test.event", "ls.core.Work", ctx.targetId(), null, Map.of());
            throw new com.lsmom.core.error.LsBizException("TEST_FAIL", "故意失败");
        }
    }

    @LsOperation(code = "ls.core.Work.testRequiresKey", concept = "ls.core.Work", name = "测试：必须幂等键", risk = Risk.C1,
        idempotency = IdempotencyPolicy.REQUIRED)
    public static class RequiresKey implements OperationHandler {
        @Override
        public Object handle(OperationContext ctx) {
            return Map.of("ok", true);
        }
    }
}
