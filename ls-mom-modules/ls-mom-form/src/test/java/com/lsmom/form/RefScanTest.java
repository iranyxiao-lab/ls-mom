package com.lsmom.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.crud.CrudFixture;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** modernize-ui-ux 4.7：扫码选中引用对象——编码精确匹配，过滤与权限同搜索。 */
class RefScanTest {

    private final FormFixture f = new FormFixture();
    private final ActorContext admin = ActionFixture.admin();

    private static long id(Map<String, Object> m) {
        return ((Number) m.get("id")).longValue();
    }

    @Test
    void 精确匹配_不存在或越过过滤时拒绝() throws Exception {
        CrudFixture.Loan loan = f.loan();
        CoreFixture.Plant plant = f.crud.action.core.plant();
        long a = id(f.crud.crud.create(loan.tool().code(), json("{\"name\":\"SC-A1\",\"orgId\":" + plant.teamA().id() + "}"), admin, null));
        id(f.crud.crud.create(loan.tool().code(), json("{\"name\":\"SC-B1\",\"orgId\":" + plant.teamB().id() + "}"), admin, null));
        RefSearchService refs = new RefSearchService(f.crud.action.concepts, f.crud.action.core.jdbc, f.crud.action.core.parties);
        JsonNode ref = json("{\"concept\":\"" + loan.tool().code() + "\",\"filter\":[{\"field\":\"orgId\",\"op\":\"eq\",\"value\":\"$context.orgId\"}]}");

        Party teamA = f.crud.action.core.employee("sc" + System.nanoTime(), plant.teamA());
        ActorContext u = f.crud.action.human(teamA, DataScopeLevel.ALL, "crud:" + loan.tool().code() + ":list");
        assertThat(refs.scan(u, ref, " SC-A1 ", null, Map.of()).id()).isEqualTo(a);
        // 只匹配完整编码；另一个班组的对象按过滤不可用
        assertThatThrownBy(() -> refs.scan(u, ref, "SC-A", null, Map.of())).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("NOT_FOUND"));
        assertThatThrownBy(() -> refs.scan(u, ref, "SC-B1", null, Map.of())).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getMessage()).contains("无权使用"));
        // 没有列表权限
        ActorContext none = f.crud.action.human(teamA, DataScopeLevel.ALL);
        assertThatThrownBy(() -> refs.scan(none, ref, "SC-A1", null, Map.of())).isInstanceOfSatisfying(LsBizException.class,
            e -> assertThat(e.getCode()).isEqualTo("ACCESS_DENIED"));
    }
}
