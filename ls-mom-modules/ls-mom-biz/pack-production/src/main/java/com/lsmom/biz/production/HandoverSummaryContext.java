package com.lsmom.biz.production;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.SummaryContextProvider;
import com.lsmom.core.error.LsBizException;
import com.lsmom.meta.concept.ManagedRecords;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 交接班摘要的上下文：交接班单与交接事项（未完成工作、已完成工作、异常）。
 * 草稿状态的交接班只有交班人可见，与交接班单本身的可见性一致。
 */
public class HandoverSummaryContext implements SummaryContextProvider {

    private final ManagedRecords records;

    public HandoverSummaryContext(ManagedRecords records) {
        this.records = records;
    }

    @Override
    public String kind() {
        return "SHIFT_HANDOVER";
    }

    @Override
    public Context load(String subjectId, ActorContext actor) {
        long id = Long.parseLong(subjectId);
        Map<String, Object> h = records.row(ProductionPack.HANDOVER, id);
        Long org = num(h.get("orgId"));
        boolean draft = "DRAFT".equals(h.get("status"));
        if (!actor.dataScope().allowsOrg(org) || draft && !actor.superAdmin() && !Objects.equals(num(h.get("createdBy")), actor.effectiveParty())) {
            throw LsBizException.notFound("交接班", subjectId);
        }
        List<Fact> facts = new ArrayList<>();
        String label = h.get("shiftDate") + " " + h.get("shift");
        facts.add(new Fact(new Ref(ProductionPack.HANDOVER, subjectId, label), "交接班 " + label + "，状态 " + h.get("status")
            + (blank(h.get("summary")) ? "" : "；交班摘要：" + h.get("summary"))
            + (blank(h.get("issueSummary")) ? "" : "；异常摘要：" + h.get("issueSummary")), h.get("createdAt") instanceof java.sql.Timestamp t ? t.toLocalDateTime() : (LocalDateTime) h.get("createdAt")));
        for (Map<String, Object> it : records.children(ProductionPack.HANDOVER_ITEM, id)) {
            String kind = String.valueOf(it.get("kind"));
            String text = switch (kind) {
                case "UNFINISHED" -> "未完成（移交接班）：";
                case "DONE" -> "已完成：";
                case "ISSUE" -> "未关闭异常：";
                default -> "事项：";
            } + it.get("title") + (it.get("workStatus") == null ? "" : "（" + it.get("workStatus") + "）") + (blank(it.get("note")) ? "" : "；" + it.get("note"));
            Ref ref = it.get("workItem") != null ? new Ref("ls.core.Work", String.valueOf(num(it.get("workItem"))), String.valueOf(it.get("title")))
                : it.get("issueId") != null ? new Ref("ls.core.Issue", String.valueOf(num(it.get("issueId"))), String.valueOf(it.get("title")))
                : new Ref(ProductionPack.HANDOVER_ITEM, String.valueOf(it.get("id")), String.valueOf(it.get("title")));
            facts.add(new Fact(ref, text, null));
        }
        return new Context(ProductionPack.HANDOVER, subjectId, "交接班 " + label, org, facts);
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static Long num(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }
}
