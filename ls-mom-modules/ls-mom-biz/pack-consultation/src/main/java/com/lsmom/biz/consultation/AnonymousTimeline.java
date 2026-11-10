package com.lsmom.biz.consultation;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.timeline.TimelineRedactor;
import com.lsmom.action.timeline.TimelineService;
import com.lsmom.meta.concept.ManagedRecords;

import java.util.List;

import static com.lsmom.biz.consultation.ConsultationPack.AUDIT;
import static com.lsmom.biz.consultation.ConsultationPack.OPINION;
import static com.lsmom.biz.consultation.ConsultationPack.SUBMIT;
import static com.lsmom.biz.consultation.ConsultationPack.TOPIC;
import static com.lsmom.biz.consultation.ConsultationSupport.num;
import static com.lsmom.biz.consultation.ConsultationSupport.yes;

/** 匿名议题的意见：时间轴上隐藏提交事件的操作人；具备审计权限的人仍可看到。领域事件与审计记录本身保留提交主体。 */
public class AnonymousTimeline implements TimelineRedactor {

    private final ManagedRecords records;

    public AnonymousTimeline(ManagedRecords records) {
        this.records = records;
    }

    @Override
    public List<TimelineService.Entry> redact(String concept, String id, List<TimelineService.Entry> entries, ActorContext viewer) {
        if (!OPINION.equals(concept) || viewer.hasPermission(AUDIT)) return entries;
        var opinion = records.find(OPINION, Long.parseLong(id));
        if (opinion.isEmpty() || !yes(records.row(TOPIC, num(opinion.get().get("topic"))).get("anonymous"))) return entries;
        return entries.stream().map(e -> SUBMIT.equals(e.type()) ? TimelineService.withoutActor(e) : e).toList();
    }
}
