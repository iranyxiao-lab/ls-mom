package com.lsmom.ui.search;

import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.action.gateway.InputSchemaValidator;
import com.lsmom.core.event.DomainEvent;
import com.lsmom.core.event.OutboxDestination;
import com.lsmom.core.org.DataScopeLevel;
import com.lsmom.core.party.Party;
import com.lsmom.crud.CrudFixture;
import com.lsmom.crud.metric.NamedQueryRegistry;
import com.lsmom.test.CoreFixture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.lsmom.action.ActionFixture.json;
import static org.assertj.core.api.Assertions.assertThat;

/** modernize-ui-ux 3.4：全局搜索读模型——Outbox 增量、拼音与首字母、编号精确命中优先、数据域与对象类型权限。 */
class GlobalSearchTest {

    /** 夹具构造时还没有索引器（它依赖夹具的 Concept 注册表），先放一个转发的订阅方。 */
    private static final class Late implements OutboxDestination {
        SearchIndexDestination target;

        public String name() {
            return "ui.search-index";
        }

        public boolean accepts(String t) {
            return true;
        }

        public void deliver(DomainEvent e) {
            target.deliver(e);
        }
    }

    private final Late late = new Late();
    private final CrudFixture f = new CrudFixture(List.of(late));
    private final ActionFixture a = f.action;
    private final CoreFixture core = a.core;
    private final SearchIndexer indexer = new SearchIndexer(core.jdbc, a.concepts);
    private final NamedQueryRegistry queries = new NamedQueryRegistry(core.jdbc, new InputSchemaValidator(), a.txm);
    private final String mark = Long.toString(System.nanoTime(), 36).replaceAll("[^a-z0-9]", "");

    {
        late.target = new SearchIndexDestination(indexer);
        queries.register(new GlobalSearch().globalSearchQuery());
    }

    private void drain() {
        for (int i = 0; i < 20; i++) if (a.outbox.deliverBatch(200) == 0) break;
    }

    private List<Map<String, Object>> search(String q, List<String> types, ActorContext actor) {
        return queries.execute(GlobalSearch.QUERY, GlobalSearch.params(q, types, 20), actor).rows();
    }

    private static List<Object> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("object_id")).toList();
    }

    @Test
    void 工作经Outbox入索引_拼音首字母可搜_编号精确命中排第一_改名删除随之更新() {
        CoreFixture.Plant p = core.plant();
        String title = "液压泵检修" + mark;
        String id = a.newWork(ActionFixture.admin(), p.teamA(), title);
        String code = core.jdbc.queryForObject("select code from core_work_item where id = ?", String.class, Long.parseLong(id));
        drain();
        List<String> work = List.of(SearchIndexer.WORK);
        assertThat(ids(search("yeyabeng", work, ActionFixture.admin()))).contains(id);
        assertThat(ids(search("yybjx" + mark, work, ActionFixture.admin()))).contains(id);
        assertThat(ids(search("泵检修" + mark, work, ActionFixture.admin()))).containsExactly(id);

        // 另一条工作标题里带着这个编号：编号完全一致的排在前面
        String other = a.newWork(ActionFixture.admin(), p.teamA(), "参见 " + code + " 的复检" + mark);
        drain();
        List<Map<String, Object>> byCode = search(code, work, ActionFixture.admin());
        assertThat(ids(byCode)).contains(id, other);
        assertThat(byCode.get(0)).containsEntry("object_id", id).containsEntry("rank", 0);

        // 对象变化后重建：改标题 → 旧词搜不到；记录消失 → 索引行移除
        core.jdbc.update("update core_work_item set title = ? where id = ?", "空压机保养" + mark, Long.parseLong(id));
        indexer.reindex(SearchIndexer.WORK, id);
        assertThat(ids(search("泵检修" + mark, work, ActionFixture.admin()))).doesNotContain(id);
        assertThat(ids(search("kyjby" + mark, work, ActionFixture.admin()))).containsExactly(id);
    }

    @Test
    void 只返回数据域内的对象_无台账列表权限的类型不出现() {
        CoreFixture.Plant p = core.plant();
        String inA = a.newWork(ActionFixture.admin(), p.teamA(), "甲班巡检" + mark);
        String inB = a.newWork(ActionFixture.admin(), p.teamB(), "乙班巡检" + mark);
        drain();
        Party member = core.employee("甲班员工", p.teamA());
        ActorContext teamA = a.human(member, DataScopeLevel.TEAM);
        assertThat(ids(search("巡检" + mark, List.of(SearchIndexer.WORK), teamA))).containsExactly(inA);
        assertThat(ids(search("巡检" + mark, List.of(SearchIndexer.WORK), ActionFixture.admin()))).contains(inA, inB);

        // 台账：新增经 Outbox 入索引；只有有列表权限的类型参与搜索
        CrudFixture.Loan loan = f.loanLedger();
        String tool = loan.tool().code();
        long toolId = ((Number) f.crud.create(tool, json("{\"name\":\"扭力扳手" + mark + "\",\"orgId\":" + p.teamA().id() + "}"), ActionFixture.admin(), null)
            .get("id")).longValue();
        drain();
        List<String> ledgers = List.of(tool);
        assertThat(GlobalSearch.allowedTypes(ledgers, teamA)).containsExactly(SearchIndexer.WORK, SearchIndexer.PARTY);
        ActorContext withList = a.human(member, DataScopeLevel.TEAM, "crud:" + tool + ":list");
        assertThat(GlobalSearch.allowedTypes(ledgers, withList)).contains(tool);
        assertThat(ids(search("nlbs" + mark, GlobalSearch.allowedTypes(ledgers, teamA), teamA))).isEmpty();
        assertThat(ids(search("nlbs" + mark, GlobalSearch.allowedTypes(ledgers, withList), withList))).containsExactly(String.valueOf(toolId));

        // 删除经 Outbox 移出索引
        int version = ((Number) f.crud.get(tool, toolId, ActionFixture.admin()).get("version")).intValue();
        f.crud.delete(tool, toolId, version, ActionFixture.admin());
        drain();
        assertThat(core.jdbc.queryForObject("select count(*) from ui_search_index where object_type = ? and object_id = ?", Long.class, tool,
            String.valueOf(toolId))).isZero();
    }

    @Test
    void 补建收录人员与台账_清理已删除对象() {
        CoreFixture.Plant p = core.plant();
        Party person = core.employee("张伟" + mark, p.teamA());
        String ghost = "ghost" + mark;
        core.jdbc.update("insert into ui_search_index (object_type, object_id, title, search_text) values (?, ?, '已删除', 'x')", SearchIndexer.WORK, ghost);
        indexer.backfill();
        List<Map<String, Object>> rows = search("zhangwei" + mark, List.of(SearchIndexer.PARTY), ActionFixture.admin());
        assertThat(ids(rows)).containsExactly(String.valueOf(person.id()));
        assertThat(rows.get(0).get("subtitle")).isEqualTo(p.teamA().name());
        assertThat(core.jdbc.queryForObject("select count(*) from ui_search_index where object_id = ?", Long.class, ghost)).isZero();
        // 其他班组的人看不到
        ActorContext teamB = a.human(core.employee("乙班员工", p.teamB()), DataScopeLevel.TEAM);
        assertThat(search("zw" + mark, List.of(SearchIndexer.PARTY), teamB)).isEmpty();
    }
}
