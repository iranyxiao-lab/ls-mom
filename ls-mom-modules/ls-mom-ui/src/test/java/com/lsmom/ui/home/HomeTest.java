package com.lsmom.ui.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.InputSchemaValidator;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.crud.metric.NamedQueryRegistry;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** modernize-ui-ux 3.9：首页卡片布局（角色默认、个人调整、恢复默认）与卡片数据（命名 Query，数据域与本人过滤）。 */
class HomeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ActionFixture a = new ActionFixture();
    private final CoreFixture core = a.core;
    private final HomeService home = new HomeService(core.jdbc);
    private final NamedQueryRegistry queries = new NamedQueryRegistry(core.jdbc, new InputSchemaValidator(), a.txm);
    private final String mark = Long.toString(System.nanoTime(), 36);

    private static List<String> codes(com.fasterxml.jackson.databind.JsonNode cards) {
        return java.util.stream.StreamSupport.stream(cards.spliterator(), false).map(c -> c.get("code").asText()).toList();
    }

    @Test
    void 角色默认_个人调整_恢复默认() throws Exception {
        long user = 910_000_000L + System.nanoTime() % 1_000_000;
        // 没有角色布局时用通用默认
        HomeService.Layout d = home.layout(user, List.of("no_such_role"));
        assertThat(d.source()).isEqualTo("role");
        assertThat(codes(d.cards())).contains("ls.home.myReviews", "ls.home.teamWork", "ls.home.openIssues");

        String role = "r" + mark.replaceAll("[^a-z0-9]", "");
        core.jdbc.update("insert into sys_role (role_id, tenant_id, role_name, role_key, role_sort, status, del_flag) values (?, '000000', ?, ?, 99, '0', '0')",
            900_000 + System.nanoTime() % 90_000, "测试角色" + mark, role);
        assertThat(home.saveRole(role, JSON.readTree("{\"cards\":[{\"code\":\"ls.home.teamWork\"},{\"code\":\"ls.home.openIssues\"}]}"), 1L)).isEqualTo(1);
        HomeService.Layout r = home.layout(user, List.of("no_such_role", role));
        assertThat(r.roleKey()).isEqualTo(role);
        assertThat(codes(r.cards())).containsExactly("ls.home.teamWork", "ls.home.openIssues");

        // 个人：隐藏一张、调整顺序 → 保持；恢复默认 → 回到角色布局
        home.saveMine(user, JSON.readTree("{\"cards\":[{\"code\":\"ls.home.openIssues\"},{\"code\":\"ls.home.teamWork\",\"hidden\":true},{\"code\":\"ls.home.openIssues\"}]}"));
        HomeService.Layout mine = home.layout(user, List.of(role));
        assertThat(mine.source()).isEqualTo("personal");
        assertThat(codes(mine.cards())).as("去重").containsExactly("ls.home.openIssues", "ls.home.teamWork");
        assertThat(mine.cards().get(1).get("hidden").asBoolean()).isTrue();
        assertThat(codes(mine.roleCards())).containsExactly("ls.home.teamWork", "ls.home.openIssues");
        home.resetMine(user);
        assertThat(home.layout(user, List.of(role)).source()).isEqualTo("role");

        // 角色布局再次设置：新版本，旧版本保留
        assertThat(home.saveRole(role, JSON.readTree("{\"cards\":[{\"code\":\"ls.home.quickLinks\"}]}"), 1L)).isEqualTo(2);
        assertThat(core.jdbc.queryForList("select status from meta_view where code = ? order by version", String.class, "home." + role))
            .containsExactly("RETIRED", "PUBLISHED");

        assertThatThrownBy(() -> home.saveMine(user, JSON.readTree("{\"cards\":[{\"code\":\"<script>\"}]}"))).isInstanceOf(LsBizException.class);
        assertThatThrownBy(() -> home.saveRole("no_such_role_" + mark.replaceAll("[^a-z0-9]", ""), JSON.readTree("{\"cards\":[]}"), 1L))
            .hasMessageContaining("角色");
    }

    @Test
    void 卡片数据_本班组今日工作只含本班组_待我审核只含本人() {
        HomeQueries q = new HomeQueries();
        for (var d : List.of(q.homeTeamWorkQuery(), q.homeMyReviewsQuery(), q.homeOpenIssuesQuery())) {
            assertThat(queries.check(d)).as(d.code()).isEmpty();
            queries.register(d);
        }
        CoreFixture.Plant p = core.plant();
        Party leader = core.employee("甲班长" + mark, p.teamA());
        ActorContext me = a.human(leader, DataScopeLevel.TEAM);
        String wa = a.newWork(ActionFixture.admin(), p.teamA(), "甲班今日" + mark);
        String wb = a.newWork(ActionFixture.admin(), p.teamB(), "乙班今日" + mark);
        for (String w : List.of(wa, wb)) {
            core.jdbc.update("update core_work_item set status = 'IN_PROGRESS' where id = ?", Long.parseLong(w));
        }
        List<Map<String, Object>> rows = queries.execute("ls.ui.homeTeamWork", json("{\"limit\":20}"), me).rows();
        assertThat(rows).extracting(r -> String.valueOf(r.get("id"))).contains(wa).doesNotContain(wb);
        assertThat(((Number) rows.get(0).get("total")).intValue()).isEqualTo(rows.size());

        // 待我审核：分给我的、我是候选人的出现；分给别人的不出现
        Party other = core.employee("别人" + mark, p.teamA());
        String biz = "t" + mark;
        core.jdbc.update("""
            insert into biz_task (task_id, kind, biz_type, biz_id, node_key, node_name, assignee_party, candidates, status, org_unit_id)
            values (?, 'TASK', 'ls.test', ?, 'review', '审核', ?, '[]', 'TODO', ?), (?, 'TASK', 'ls.test', ?, 'review', '审核', null, to_jsonb(array[?::bigint]), 'TODO', ?),
                   (?, 'TASK', 'ls.test', ?, 'review', '审核', ?, '[]', 'TODO', ?)""",
            biz + "a", biz, leader.id(), p.teamA().id(), biz + "b", biz, leader.id(), p.teamB().id(), biz + "c", biz, other.id(), p.teamA().id());
        List<Object> mine = queries.execute("ls.ui.homeMyReviews", json("{\"limit\":20}"), me).rows().stream()
            .filter(r -> biz.equals(r.get("bizId"))).map(r -> r.get("taskId")).toList();
        assertThat(mine).containsExactlyInAnyOrder(biz + "a", biz + "b");
    }
}
