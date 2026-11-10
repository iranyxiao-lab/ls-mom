package com.lsmom.action.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.lsmom.action.api.LsOperation;
import com.lsmom.action.api.OperationContext;
import com.lsmom.action.api.OperationHandler;
import com.lsmom.action.api.Risk;
import com.lsmom.action.api.Exposure;
import com.lsmom.core.document.Classification;
import com.lsmom.core.document.DocumentLink;
import com.lsmom.core.document.DocumentService;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkItemService;
import com.lsmom.core.work.WorkRelationService;
import com.lsmom.core.work.WorkReport;
import com.lsmom.core.work.WorkReportService;
import com.lsmom.core.work.WorkType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 核心对象的 Java Operation（创建类与跨对象动作；状态推进由状态机迁移完成）。 */
public final class CoreOperations {

    private CoreOperations() {
    }

    static String str(JsonNode in, String f) {
        return in.hasNonNull(f) ? in.get(f).asText() : null;
    }

    static Long lng(JsonNode in, String f) {
        return in.hasNonNull(f) ? in.get(f).asLong() : null;
    }

    static LocalDateTime time(JsonNode in, String f) {
        return in.hasNonNull(f) ? LocalDateTime.parse(in.get(f).asText()) : null;
    }

    @Component
    @LsOperation(code = "ls.core.Work.create", concept = "ls.core.Work", name = "新建工作", risk = Risk.C1, exposure = Exposure.DRAFT,
        permission = "lsmom:work:create", inputSchema = """
        {"type":"object","required":["workType","title","orgUnitId"],"properties":{
          "workType":{"enum":["PRODUCTION","MAINTENANCE","INSPECTION","IMPROVEMENT","OTHER"]},
          "title":{"type":"string","minLength":1,"maxLength":200},"description":{"type":"string"},
          "orgUnitId":{"type":"integer"},"constraintProfile":{"type":"string"},"priority":{"type":"integer","minimum":1,"maximum":5},
          "plannedStart":{"type":"string"},"plannedEnd":{"type":"string"}}}""")
    public static class CreateWork implements OperationHandler {
        private final WorkItemService works;

        public CreateWork(WorkItemService works) {
            this.works = works;
        }

        @Override
        public Object handle(OperationContext ctx) {
            JsonNode in = ctx.input();
            Long org = lng(in, "orgUnitId");
            if (!ctx.actor().dataScope().allowsOrg(org) && !ctx.actor().dataScope().all()) {
                throw new com.lsmom.core.error.LsBizException("ACCESS_DENIED", "不能在数据范围以外的组织创建工作");
            }
            WorkItem w = works.create(new WorkItemService.CreateWorkItem(WorkType.valueOf(str(in, "workType")), str(in, "title"),
                in.hasNonNull("description") ? in.get("description").asText() : "", org, str(in, "constraintProfile"),
                in.hasNonNull("priority") ? in.get("priority").asInt() : null, time(in, "plannedStart"), time(in, "plannedEnd"),
                null, null, null, null, ctx.actor().effectiveParty()));
            ctx.target(String.valueOf(w.id()), w.version(), w.status().name(), w.orgUnitId());
            return Map.of("id", w.id(), "code", w.code());
        }
    }

    /** 按调用者数据域查询工作（只读）；指定数据域以外的组织直接拒绝。 */
    @Component
    @LsOperation(code = "ls.core.Work.find", concept = "ls.core.Work", name = "查询工作", risk = Risk.C0, exposure = Exposure.QUERY,
        permission = "lsmom:work:execute", inputSchema = """
        {"type":"object","properties":{"orgUnitId":{"type":"integer"},
          "status":{"enum":["DRAFT","READY","ASSIGNED","IN_PROGRESS","PENDING_CONFIRM","DONE","BLOCKED","CANCELLED"]},
          "keyword":{"type":"string","maxLength":100},"limit":{"type":"integer","minimum":1,"maximum":100}}}""")
    public static class FindWork implements OperationHandler {
        private final WorkItemService works;

        public FindWork(WorkItemService works) {
            this.works = works;
        }

        @Override
        public Object handle(OperationContext ctx) {
            JsonNode in = ctx.input();
            Long org = lng(in, "orgUnitId");
            var scope = ctx.actor().dataScope();
            if (org != null && !scope.all() && !scope.allowsOrg(org)) {
                throw new com.lsmom.core.error.LsBizException("ACCESS_DENIED", "无权查看该组织的工作", Map.of("orgUnitId", org));
            }
            List<WorkItem> list = works.list(new WorkItemService.WorkQuery(null,
                in.hasNonNull("status") ? com.lsmom.core.work.WorkStatus.valueOf(str(in, "status")) : null,
                in.hasNonNull("keyword") ? str(in, "keyword") : null, org, 1, in.hasNonNull("limit") ? in.get("limit").asInt() : 20), scope);
            return Map.of("total", list.size(), "rows", list.stream().map(w -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", w.id());
                m.put("code", w.code());
                m.put("title", w.title());
                m.put("status", w.status().name());
                m.put("orgUnitId", w.orgUnitId());
                m.put("plannedEnd", w.plannedEnd());
                return m;
            }).toList());
        }
    }

    @Component
    @LsOperation(code = "ls.core.Work.split", concept = "ls.core.Work", name = "拆分工作", risk = Risk.C1,
        permission = "lsmom:work:edit", requiresVersion = true, inputSchema = """
        {"type":"object","required":["titles"],"properties":{"titles":{"type":"array","minItems":1,"items":{"type":"string","minLength":1}}}}""")
    public static class SplitWork implements OperationHandler {
        private final WorkRelationService relations;

        public SplitWork(WorkRelationService relations) {
            this.relations = relations;
        }

        @Override
        public Object handle(OperationContext ctx) {
            List<String> titles = new ArrayList<>();
            ctx.input().get("titles").forEach(t -> titles.add(t.asText()));
            List<WorkItem> kids = relations.split(ctx.targetIdAsLong(), titles, ctx.actor().effectiveParty());
            ctx.eventPayload().put("children", kids.stream().map(WorkItem::code).toList());
            return Map.of("children", kids.stream().map(k -> Map.of("id", k.id(), "code", k.code())).toList());
        }
    }

    @Component
    @LsOperation(code = "ls.core.Work.link", concept = "ls.core.Work", name = "关联工作", risk = Risk.C1,
        permission = "lsmom:work:edit", inputSchema = """
        {"type":"object","required":["toId","relationType"],"properties":{"toId":{"type":"integer"},
          "relationType":{"enum":["PARENT","DEPENDS","RELATED","BLOCKS"]}}}""")
    public static class LinkWork implements OperationHandler {
        private final WorkRelationService relations;

        public LinkWork(WorkRelationService relations) {
            this.relations = relations;
        }

        @Override
        public Object handle(OperationContext ctx) {
            relations.link(ctx.targetIdAsLong(), ctx.input().get("toId").asLong(),
                WorkRelationService.RelationType.valueOf(ctx.text("relationType")));
            ctx.eventPayload().put("to", ctx.input().get("toId").asLong());
            return Map.of("linked", true);
        }
    }

    static final String REPORT_SCHEMA = """
        {"type":"object","properties":{"reportId":{"type":"integer"},
          "progress":{"type":"number","minimum":0,"maximum":100},"laborMinutes":{"type":"integer","minimum":0},
          "outputQty":{"type":"number"},"outputUnit":{"type":"string","maxLength":32},"remark":{"type":"string","maxLength":2000},
          "consumption":{"type":"array","items":{"type":"object","required":["name"],"properties":{
            "name":{"type":"string"},"qty":{"type":"number"},"unit":{"type":"string"}}}}}}""";

    static WorkReportService.ReportInput reportInput(JsonNode in) {
        List<WorkReport.Consumption> cons = new ArrayList<>();
        if (in.has("consumption")) in.get("consumption").forEach(c -> cons.add(new WorkReport.Consumption(c.get("name").asText(),
            c.hasNonNull("qty") ? c.get("qty").decimalValue() : null, str(c, "unit"))));
        return new WorkReportService.ReportInput(in.hasNonNull("progress") ? in.get("progress").decimalValue() : null,
            in.hasNonNull("laborMinutes") ? in.get("laborMinutes").asInt() : null,
            in.hasNonNull("outputQty") ? in.get("outputQty").decimalValue() : null, str(in, "outputUnit"), cons, str(in, "remark"));
    }

    @Component
    @LsOperation(code = "ls.core.Work.saveReport", concept = "ls.core.Work", name = "保存填报草稿", risk = Risk.C0,
        exposure = Exposure.DRAFT, permission = "lsmom:work:execute", inputSchema = REPORT_SCHEMA)
    public static class SaveReport implements OperationHandler {
        private final WorkReportService reports;

        public SaveReport(WorkReportService reports) {
            this.reports = reports;
        }

        @Override
        public Object handle(OperationContext ctx) {
            WorkReport r = reports.saveDraft(ctx.targetIdAsLong(), ctx.longValue("reportId"), ctx.actor().effectiveParty(), reportInput(ctx.input()));
            ctx.eventPayload().put("reportId", r.id());
            return Map.of("reportId", r.id(), "status", r.status());
        }
    }

    @Component
    @LsOperation(code = "ls.core.Work.submitReport", concept = "ls.core.Work", name = "提交填报", risk = Risk.C1,
        permission = "lsmom:work:execute", inputSchema = REPORT_SCHEMA)
    public static class SubmitReport implements OperationHandler {
        private final WorkReportService reports;

        public SubmitReport(WorkReportService reports) {
            this.reports = reports;
        }

        @Override
        public Object handle(OperationContext ctx) {
            Long reportId = ctx.longValue("reportId");
            WorkReport draft = reportId != null && !hasContent(ctx.input())
                ? reports.get(reportId)
                : reports.saveDraft(ctx.targetIdAsLong(), reportId, ctx.actor().effectiveParty(), reportInput(ctx.input()));
            if (!draft.workItemId().equals(ctx.targetIdAsLong())) {
                throw new com.lsmom.core.error.LsBizException("REPORT_MISMATCH", "填报不属于该工作");
            }
            WorkReport r = reports.submit(draft.id());
            ctx.eventPayload().put("reportId", r.id());
            if (r.outputQty() != null) ctx.eventPayload().put("outputQty", r.outputQty());
            if (r.outputUnit() != null) ctx.eventPayload().put("outputUnit", r.outputUnit());
            ctx.emit("ls.core.WorkReport.submitted", "ls.core.WorkReport", String.valueOf(r.id()), ctx.targetOrgUnitId(),
                Map.of("workItemId", r.workItemId(), "outputQty", r.outputQty() == null ? BigDecimal.ZERO : r.outputQty(),
                    "laborMinutes", r.laborMinutes() == null ? 0 : r.laborMinutes()));
            return Map.of("reportId", r.id(), "status", r.status());
        }

        private static boolean hasContent(JsonNode in) {
            return in.size() > 1;
        }
    }

    @Component
    @LsOperation(code = "ls.core.Issue.raise", concept = "ls.core.Issue", name = "登记异常", risk = Risk.C1, exposure = Exposure.DRAFT,
        permission = "lsmom:issue:raise", inputSchema = """
        {"type":"object","required":["type","title","orgUnitId"],"properties":{
          "type":{"type":"string","minLength":1,"maxLength":32},"title":{"type":"string","minLength":1,"maxLength":200},
          "description":{"type":"string"},"severity":{"enum":["LOW","MEDIUM","HIGH","CRITICAL"]},"orgUnitId":{"type":"integer"},
          "ownerParty":{"type":"integer"},"dueAt":{"type":"string"},"sourceType":{"type":"string"},"sourceKey":{"type":"string","maxLength":200},
          "links":{"type":"array","items":{"type":"object","required":["objectType","objectId"],"properties":{
            "objectType":{"type":"string"},"objectId":{"type":"string"}}}}}}""")
    public static class RaiseIssue implements OperationHandler {
        private final IssueService issues;

        public RaiseIssue(IssueService issues) {
            this.issues = issues;
        }

        @Override
        public Object handle(OperationContext ctx) {
            JsonNode in = ctx.input();
            List<IssueService.ObjectRef> links = new ArrayList<>();
            if (in.has("links")) in.get("links").forEach(l -> links.add(new IssueService.ObjectRef(l.get("objectType").asText(), l.get("objectId").asText())));
            IssueService.RaiseResult r = issues.raise(new IssueService.RaiseIssue(str(in, "type"), str(in, "title"), str(in, "description"),
                str(in, "severity"), lng(in, "orgUnitId"), str(in, "sourceType"), str(in, "sourceKey"), ctx.actor().effectiveParty(),
                lng(in, "ownerParty"), time(in, "dueAt"), null, null, links));
            Issue i = r.issue();
            ctx.target(String.valueOf(i.id()), i.version(), i.status().name(), i.orgUnitId());
            if (r.deduplicated()) {
                ctx.warn("同一来源的异常已存在，已累计发生次数");
                ctx.eventPayload().put("deduplicated", true);
                ctx.eventPayload().put("occurrenceCount", i.occurrenceCount());
            }
            return Map.of("id", i.id(), "code", i.code(), "deduplicated", r.deduplicated());
        }
    }

    @Component
    @LsOperation(code = "ls.core.Issue.createRectification", concept = "ls.core.Issue", name = "生成整改工作", risk = Risk.C1,
        exposure = Exposure.APPROVAL_REQUIRED, version = 2, permission = "lsmom:issue:handle", inputSchema = """
        {"type":"object","required":["titles"],"properties":{"titles":{"type":"array","minItems":1,"items":{"type":"string","minLength":1}}}}""")
    public static class CreateRectification implements OperationHandler {
        private final IssueService issues;

        public CreateRectification(IssueService issues) {
            this.issues = issues;
        }

        @Override
        public Object handle(OperationContext ctx) {
            List<String> titles = new ArrayList<>();
            ctx.input().get("titles").forEach(t -> titles.add(t.asText()));
            List<WorkItem> works = issues.createRectifications(ctx.targetIdAsLong(), titles, ctx.actor().effectiveParty());
            ctx.eventPayload().put("works", works.stream().map(WorkItem::code).toList());
            for (WorkItem w : works) {
                ctx.emit("ls.core.Work.create", "ls.core.Work", String.valueOf(w.id()), w.orgUnitId(),
                    Map.of("source", "ISSUE", "issueId", ctx.targetIdAsLong()));
            }
            return Map.of("works", works.stream().map(w -> Map.of("id", w.id(), "code", w.code())).toList());
        }
    }

    @Component
    @LsOperation(code = "ls.core.DocumentLink.attach", concept = "ls.core.DocumentLink", name = "关联附件", risk = Risk.C0, version = 2,
        permission = "", inputSchema = """
        {"type":"object","required":["ossId","objectType","objectId"],"properties":{"ossId":{"type":["integer","string"],"pattern":"^[0-9]{1,19}$"},
          "objectType":{"type":"string"},"objectId":{"type":"string"},"purpose":{"type":"string"},
          "classification":{"enum":["PUBLIC","INTERNAL","CONFIDENTIAL","SECRET"]},"orgUnitId":{"type":"integer"}}}""")
    public static class AttachDocument implements OperationHandler {
        private final DocumentService docs;

        public AttachDocument(DocumentService docs) {
            this.docs = docs;
        }

        @Override
        public Object handle(OperationContext ctx) {
            JsonNode in = ctx.input();
            DocumentLink d = docs.attach(new DocumentService.AttachCommand(in.get("ossId").asLong(), str(in, "objectType"), str(in, "objectId"),
                str(in, "purpose"), in.hasNonNull("classification") ? Classification.valueOf(str(in, "classification")) : null,
                lng(in, "orgUnitId"), ctx.actor().effectiveParty()));
            ctx.target(String.valueOf(d.id()), d.docVersion(), null, d.orgUnitId());
            ctx.emit("ls.core.DocumentLink.attached", d.objectType().equals("WORK") ? "ls.core.Work" : d.objectType().equals("ISSUE")
                ? "ls.core.Issue" : d.objectType(), d.objectId(), d.orgUnitId(), Map.of("fileName", d.fileName(), "purpose", d.purpose()));
            return Map.of("id", d.id(), "version", d.docVersion());
        }
    }
}
