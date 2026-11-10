package com.lsmom.biz.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.lsmom.action.ActionFixture;
import com.lsmom.action.api.ActorContext;
import com.lsmom.biz.asset.AssetTimeline;
import com.lsmom.core.error.LsBizException;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueStatus;
import com.lsmom.core.observation.Observation;
import com.lsmom.core.observation.ObservationService;
import com.lsmom.core.org.OrgUnit;
import com.lsmom.core.party.Party;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkStatus;
import com.lsmom.core.work.WorkType;
import com.lsmom.core.org.DataScopeLevel;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static com.lsmom.biz.quality.QualityPack.NC;
import static com.lsmom.biz.quality.QualityPack.RECORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 14.4 切片 C 黄金场景：由数据集 {@code golden/slice-c.yaml} 驱动。
 * DOM-04 质量记录可独立或同时关联多个对象并进入各对象时间轴；高严重度不符合项条件必填；
 * 受控整改：登记 → 车间审核（问题描述只读）→ 整改工作派给班组负责人 → 证据 → 验证不通过 → 再提交 → 真人终判关闭（Agent 被拒）。
 */
class SliceCGoldenScenariosTest {

    private final QualityPackTest base = new QualityPackTest();
    private final JsonNode ds = load();
    private final Map<String, Party> people = new HashMap<>();

    private static JsonNode load() {
        try (InputStream in = new ClassPathResource("golden/slice-c.yaml").getInputStream()) {
            return new YAMLMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    SliceCGoldenScenariosTest() {
        for (JsonNode p : ds.get("people")) {
            OrgUnit org = "teamA".equals(p.get("org").asText()) ? base.f.plant.teamA() : base.f.plant.workshop();
            people.put(p.get("key").asText(), base.f.core.employee(p.get("name").asText(), org));
        }
    }

    private ActorContext as(String key) {
        // 班组成员只有本班组数据域
        return "owner".equals(key) ? base.f.action.human(people.get(key), DataScopeLevel.TEAM, QualityPackTest.PERMS) : base.user(people.get(key));
    }

    private long workshop() {
        return base.f.plant.workshop().id();
    }

    @Test
    void 质量记录可独立或多对象关联() {
        ActorContext qa = as("qa");
        String work = base.f.action.newWork(ActionFixture.admin(), base.f.plant.teamA(), "焊接 A 段");
        long asset = base.asset(qa);
        for (JsonNode r : ds.get("records")) {
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("title", r.get("title").asText()).put("recordType", r.get("recordType").asText()).put("result", r.get("result").asText())
                .put("orgId", workshop());
            ArrayNode links = body.putArray("qualityRecordLink");
            for (JsonNode l : r.get("links")) {
                boolean isWork = "WORK".equals(l.asText());
                links.addObject().put("objectType", l.asText()).put("objectId", isWork ? work : String.valueOf(asset)).put("label", isWork ? "焊接 A 段" : "焊机");
            }
            long id = ((Number) base.f.crud.crud.create(RECORD, body, qa, null).get("id")).longValue();
            base.step(RECORD + ".submit", qa, RECORD, id, "{}");
            assertThat(base.records.row(RECORD, id)).containsEntry("status", "SUBMITTED");
            String title = r.get("title").asText();
            List<String> types = StreamSupport.stream(r.get("links").spliterator(), false).map(JsonNode::asText).toList();
            boolean onWork = new TimelineService(base.f.core.jdbc).of("ls.core.Work", work).stream()
                .anyMatch(x -> x.type().equals(RECORD + ".linked") && String.valueOf(x.payload().get("title")).contains(title));
            boolean onAsset = new AssetTimeline(base.f.core.jdbc, base.records).of(asset, 100).stream()
                .anyMatch(m -> "EVENT".equals(m.get("kind")) && String.valueOf(m.get("title")).contains(title));
            assertThat(onWork).as(title + " 在工作时间轴").isEqualTo(types.contains("WORK"));
            assertThat(onAsset).as(title + " 在设备时间轴").isEqualTo(types.contains("ASSET"));
        }
    }

    @Test
    void 高严重度不符合项条件必填() {
        JsonNode h = ds.get("highSeverity");
        ObjectNode body = h.get("nc").deepCopy();
        body.put("orgId", workshop());
        assertThatThrownBy(() -> base.f.crud.crud.create(NC, body, as("qa"), null))
            .satisfies(e -> assertThat(((LsBizException) e).getCode()).isEqualTo("VALIDATION_FAILED"))
            .satisfies(e -> assertThat(QualityPackTest.fields(e).keySet())
                .containsAll(StreamSupport.stream(h.get("missing").spliterator(), false).map(JsonNode::asText).toList()));
    }

    @Test
    void 受控整改闭环_终判仅限真人() {
        JsonNode c = ds.get("controlled");
        JsonNode exp = ds.get("expect");
        ActorContext qa = as("qa");
        ObjectNode body = c.get("nc").deepCopy();
        body.put("orgId", workshop());
        long nc = ((Number) base.f.crud.crud.create(NC, body, qa, null).get("id")).longValue();
        base.step(NC + ".submit", qa, NC, nc, "{}");
        Issue issue = base.f.action.issues.get(((Number) base.records.row(NC, nc).get("issue")).longValue());
        assertThat(issue.type()).isEqualTo(exp.get("issueType").asText());

        // 车间审核：只能填审核意见、立即措施并派整改负责人；问题描述只读
        JsonNode au = c.get("audit");
        ActorContext lead = as(au.get("by").asText());
        Party owner = people.get(au.get("owner").asText());
        assertThatThrownBy(() -> base.step(NC + ".audit", lead, NC, nc,
            "{\"auditOpinion\":\"同意\",\"owner\":%d,\"description\":\"改一下\"}".formatted(owner.id())))
            .satisfies(e -> assertThat(QualityPackTest.code(e)).isEqualTo("FIELD_READONLY"));
        ObjectNode audit = JsonNodeFactory.instance.objectNode();
        audit.put("auditOpinion", au.get("auditOpinion").asText()).put("immediateAction", au.get("immediateAction").asText()).put("owner", owner.id());
        audit.set("titles", au.get("titles"));
        base.step(NC + ".audit", lead, NC, nc, audit.toString());
        WorkItem w = base.f.action.issues.rectifications(issue.id()).get(0);
        assertThat(w.type()).isEqualTo(WorkType.valueOf(exp.get("rectificationType").asText()));
        assertThat(base.f.core.works.assignedTo(w.id(), owner.id())).isTrue();

        // 整改负责人（班组数据域）可见并办理；无证据不能提交验证
        ActorContext fixer = as("owner");
        assertThat(base.f.crud.crud.get(NC, nc, fixer)).containsEntry("title", c.get("nc").get("title").asText());
        base.f.action.step("ls.core.Work.start", fixer, "ls.core.Work", String.valueOf(w.id()), "{}");
        base.f.action.step("ls.core.Work.submitConfirm", fixer, "ls.core.Work", String.valueOf(w.id()), "{}");
        base.f.action.step("ls.core.Work.confirm", lead, "ls.core.Work", String.valueOf(w.id()), "{}");
        assertThat(base.f.core.works.get(w.id()).status()).isEqualTo(WorkStatus.DONE);
        assertThatThrownBy(() -> base.step(NC + ".submitVerify", fixer, NC, nc, "{}")).extracting(QualityPackTest::code).isEqualTo("EVIDENCE_REQUIRED");
        JsonNode ev = c.get("evidence");
        new ObservationService(base.f.core.jdbc).record(new Observation(null, "WORK", String.valueOf(w.id()), ev.get("code").asText(),
            new BigDecimal(ev.get("value").asText()), null, ev.get("unit").asText(), LocalDateTime.now(), Observation.Source.MANUAL, "GOOD",
            base.f.plant.teamA().id(), owner.id()));
        base.step(NC + ".submitVerify", fixer, NC, nc, "{}");

        // 验证不通过 → 回到整改 → 再提交
        String failed = c.get("verifyFail").get("conclusion").asText();
        base.step(NC + ".verifyFail", as(c.get("verifyFail").get("by").asText()), NC, nc, "{\"conclusion\":\"" + failed + "\"}");
        assertThat(base.records.row(NC, nc)).containsEntry("status", "IN_RECTIFICATION").containsEntry("verifyConclusion", failed);
        base.step(NC + ".submitVerify", fixer, NC, nc, "{}");
        assertThat(base.f.action.issues.get(issue.id()).status()).isEqualTo(IssueStatus.PENDING_VERIFY);

        // Agent 代终判被拒，真人终判关闭
        ActorContext agent = base.agentFor(people.get("qa"));
        for (JsonNode op : exp.get("agentRejected")) {
            assertThatThrownBy(() -> base.step(NC + "." + op.asText(), agent, NC, nc, "{\"conclusion\":\"代判\"}"))
                .satisfies(e -> assertThat(e.getMessage()).contains("真人"));
        }
        String concl = c.get("close").get("conclusion").asText();
        base.step(NC + ".close", as(c.get("close").get("by").asText()), NC, nc, "{\"conclusion\":\"" + concl + "\"}");
        assertThat(base.records.row(NC, nc)).containsEntry("status", "CLOSED").containsEntry("verifyConclusion", concl)
            .containsEntry("closedBy", people.get("qa").id());
        assertThat(base.f.action.issues.get(issue.id()).status()).isEqualTo(IssueStatus.CLOSED);
    }
}
