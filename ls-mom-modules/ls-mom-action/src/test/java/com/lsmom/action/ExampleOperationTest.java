package com.lsmom.action;

import com.lsmom.core.error.LsBizException;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 5.10：按文档新增的示例 Operation 可调用（注册、校验、版本、事件、审计齐全）。 */
class ExampleOperationTest {

    private final ActionFixture f = new ActionFixture();

    @Test
    void 示例Operation按文档新增后可调用() {
        CoreFixture.Plant p = f.core.plant();
        String id = f.newWork(ActionFixture.admin(), p.teamA(), "急单");
        assertThat(f.operations.require("ls.core.Work.reprioritize").source()).isEqualTo("CODE");

        var r = f.on("ls.core.Work.reprioritize", ActionFixture.admin(), id, 0, "{\"priority\":1,\"reason\":\"客户催货\"}");
        assertThat(r.version()).isEqualTo(1);
        assertThat(f.core.works.get(Long.parseLong(id)).priority()).isEqualTo(1);
        assertThat(f.events.eventsOf("ls.core.Work", id).get(1).payload().get("reason")).isEqualTo("客户催货");
        assertThat(f.count("select count(*) from op_audit where op_code = 'ls.core.Work.reprioritize' and result = 'SUCCESS' and target_id = ?", id)).isEqualTo(1);

        assertThatThrownBy(() -> f.on("ls.core.Work.reprioritize", ActionFixture.admin(), id, 1, "{\"priority\":9}"))
            .isInstanceOfSatisfying(LsBizException.class, e -> assertThat(e.fieldErrors()).containsKey("priority"));
        assertThatThrownBy(() -> f.on("ls.core.Work.reprioritize", ActionFixture.admin(), id, 0, "{\"priority\":2}"))
            .extracting("code").isEqualTo("VERSION_CONFLICT");
    }
}
