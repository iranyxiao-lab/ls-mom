package com.lsmom.ui.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.party.Party;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** modernize-ui-ux 4.2：表单自动带入上下文（人员、主组织、班次、时间）与记住上次填写。 */
class FormContextTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final CoreFixture core = new CoreFixture();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T09:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private final FormContextService svc = new FormContextService(core.jdbc, clock);
    private final String mark = Long.toString(System.nanoTime(), 36);

    private void shiftDict() {
        Integer n = core.jdbc.queryForObject("select count(*) from sys_dict_data where dict_type = 'ls_shift'", Integer.class);
        if (n != null && n > 0) return;
        String[][] rows = {{"DAY", "08:00-16:00"}, {"EVENING", "16:00-24:00"}, {"NIGHT", "00:00-08:00"}};
        for (int i = 0; i < rows.length; i++) {
            core.jdbc.update("insert into sys_dict_data (dict_code, tenant_id, dict_sort, dict_label, dict_value, dict_type, remark) values (?, '000000', ?, ?, ?, 'ls_shift', ?)",
                980_000_000L + System.nanoTime() % 1_000_000 + i, i, rows[i][0], rows[i][0], rows[i][1]);
        }
    }

    private String form(String fieldsJson) {
        String code = "acme.t" + mark.replaceAll("[^a-z0-9]", "") + ".defectForm";
        core.jdbc.update("insert into meta_view (code, version, concept_code, view_type, display_name, status, schema_json) values (?, 1, null, 'FORM', '测试表单', 'PUBLISHED', ?::jsonb)",
            code, "{\"fields\":" + fieldsJson + "}");
        return code;
    }

    @Test
    void 上下文带出人员_主组织_班次与时间() {
        shiftDict();
        OrgUnit ws = core.plant().workshop();
        Party p = core.employee("张" + mark, ws);
        FormContextService.Context c = svc.context(1L, p.id(), null);
        assertThat(c.partyId()).isEqualTo(p.id());
        assertThat(c.orgId()).isEqualTo(ws.id());
        assertThat(c.now()).isEqualTo("2026-11-10T17:30:00");
        assertThat(c.shift()).isEqualTo("EVENING");
        assertThat(svc.shift(LocalTime.of(3, 0))).isEqualTo("NIGHT");
        assertThat(svc.shift(LocalTime.of(8, 0))).isEqualTo("DAY");
        assertThat(svc.context(1L, null, null).orgId()).isNull();
    }

    @Test
    void 只记住表单中标了remember的字段_按表单分开保存() throws Exception {
        long user = 920_000_000L + System.nanoTime() % 1_000_000;
        String code = form("[{\"key\":\"severity\",\"remember\":true},{\"key\":\"station\",\"remember\":true},{\"key\":\"title\"}]");
        assertThat(svc.remembered(user, code).size()).isZero();

        svc.remember(user, code, JSON.readTree("{\"severity\":\"HIGH\",\"station\":12}"));
        JsonNode v = svc.context(user, null, code).remembered();
        assertThat(v.get("severity").asText()).isEqualTo("HIGH");
        assertThat(v.get("station").asLong()).isEqualTo(12);
        assertThat(svc.remembered(user, "acme.other.form").size()).isZero();

        // 未标 remember 的字段、复杂值被拒；空值不保存
        assertThatThrownBy(() -> svc.remember(user, code, JSON.readTree("{\"title\":\"x\"}"))).isInstanceOf(LsBizException.class);
        assertThatThrownBy(() -> svc.remember(user, code, JSON.readTree("{\"severity\":{\"a\":1}}"))).isInstanceOf(LsBizException.class);
        assertThatThrownBy(() -> svc.remember(user, "acme.nope.form", JSON.readTree("{}"))).isInstanceOf(LsBizException.class);
        svc.remember(user, code, JSON.readTree("{\"severity\":\"LOW\",\"station\":null}"));
        assertThat(svc.remembered(user, code).has("station")).isFalse();
        assertThat(svc.remembered(user, code).get("severity").asText()).isEqualTo("LOW");
    }
}
