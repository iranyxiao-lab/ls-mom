package com.lsmom.ui.pref;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import com.lsmom.test.PgTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrefServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrefService prefs = new PrefService(PgTestSupport.jdbc());

    private static JsonNode json(String s) throws Exception {
        return JSON.readTree(s);
    }

    private static long user() {
        return 900_000_000L + System.nanoTime() % 100_000_000L;
    }

    @Test
    void 偏好合并更新_未设置时为空() throws Exception {
        long u = user();
        assertThat(prefs.get(u).prefs().size()).isZero();
        prefs.updatePrefs(u, json("{\"theme\":\"dark\",\"density\":\"compact\"}"));
        JsonNode merged = prefs.updatePrefs(u, json("{\"navType\":\"top\",\"hotkeyHints\":false}"));
        assertThat(merged.get("theme").asText()).isEqualTo("dark");
        assertThat(merged.get("navType").asText()).isEqualTo("top");
        assertThat(merged.get("hotkeyHints").asBoolean()).isFalse();
        assertThat(prefs.get(u).prefs()).isEqualTo(merged);
    }

    @Test
    void 新手引导状态_看完或跳过后记住() throws Exception {
        long u = user();
        prefs.updatePrefs(u, json("{\"tourWorkbench\":\"skipped\"}"));
        JsonNode merged = prefs.updatePrefs(u, json("{\"tourDesigner\":\"done\"}"));
        assertThat(merged.get("tourWorkbench").asText()).isEqualTo("skipped");
        assertThat(merged.get("tourDesigner").asText()).isEqualTo("done");
        assertThatThrownBy(() -> prefs.updatePrefs(u, json("{\"tourWorkbench\":\"maybe\"}"))).hasMessageContaining("取值应为");
    }

    @Test
    void 未知偏好项与非法取值被拒绝() {
        long u = user();
        assertThatThrownBy(() -> prefs.updatePrefs(u, json("{\"theme\":\"pink\"}"))).isInstanceOf(LsBizException.class).hasMessageContaining("取值应为");
        assertThatThrownBy(() -> prefs.updatePrefs(u, json("{\"script\":\"x\"}"))).hasMessageContaining("未知的偏好项");
        assertThatThrownBy(() -> prefs.updatePrefs(u, json("{\"tagsView\":\"yes\"}"))).hasMessageContaining("是或否");
        assertThat(prefs.get(u).prefs().size()).isZero();
    }

    @Test
    void 收藏与最近访问_只收站内地址_去重_有条数上限() throws Exception {
        long u = user();
        prefs.updateNav(u, json("""
            {"recent":[{"kind":"page","path":"/asset/center","title":"设备台账","sub":"台账 / 设备","at":2},
                       {"kind":"page","path":"/asset/center","title":"重复","at":1},
                       {"kind":"object","path":"/lsmom/object/work/5","title":"WI5 巡检","at":1}],
             "favorites":[{"kind":"page","path":"/quality/nc","title":"不符合项"}]}"""));
        PrefService.Prefs p = prefs.get(u);
        assertThat(p.recent()).hasSize(2);
        assertThat(p.recent().get(0).get("title").asText()).isEqualTo("设备台账");
        assertThat(p.favorites().get(0).get("path").asText()).isEqualTo("/quality/nc");

        for (String bad : new String[]{"https://evil.example/x", "//evil.example", "javascript:alert(1)"}) {
            assertThatThrownBy(() -> prefs.updateNav(u, json("{\"favorites\":[{\"path\":\"" + bad + "\",\"title\":\"x\"}]}")))
                .isInstanceOf(LsBizException.class);
        }
        StringBuilder many = new StringBuilder("{\"recent\":[");
        for (int i = 0; i <= PrefService.RECENT_MAX; i++) many.append(i == 0 ? "" : ",").append("{\"path\":\"/p/").append(i).append("\",\"title\":\"p\"}");
        assertThatThrownBy(() -> prefs.updateNav(u, json(many + "]}"))).hasMessageContaining("最多");
        // 偏好与导航互不覆盖
        prefs.updatePrefs(u, json("{\"theme\":\"light\"}"));
        assertThat(prefs.get(u).favorites()).hasSize(1);
    }
}
