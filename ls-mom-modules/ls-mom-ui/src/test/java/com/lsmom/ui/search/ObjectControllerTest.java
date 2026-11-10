package com.lsmom.ui.search;

import com.lsmom.core.org.DataScope;
import com.lsmom.core.org.DataScopeLevel;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 对象详情的数据域判断与全局搜索一致：组织在范围内（本人级除外），或本人创建。 */
class ObjectControllerTest {

    @Test
    void 数据域判断() {
        DataScope team = new DataScope(false, Set.of(10L), 7L, DataScopeLevel.TEAM);
        assertThat(ObjectController.visible(team, 10L, null)).isTrue();
        assertThat(ObjectController.visible(team, 11L, null)).isFalse();
        assertThat(ObjectController.visible(team, 11L, 7L)).isTrue();
        DataScope self = new DataScope(false, Set.of(10L), 7L, DataScopeLevel.SELF);
        assertThat(ObjectController.visible(self, 10L, 8L)).isFalse();
        assertThat(ObjectController.visible(self, 10L, 7L)).isTrue();
        assertThat(ObjectController.visible(DataScope.unrestricted(), 99L, null)).isTrue();
        assertThat(ObjectController.visible(null, null, null)).isTrue();
    }
}
