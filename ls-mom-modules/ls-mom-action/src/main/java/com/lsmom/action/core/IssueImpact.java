package com.lsmom.action.core;

import com.lsmom.action.api.OperationImpactProvider;
import com.lsmom.core.issue.Issue;
import com.lsmom.core.issue.IssueService;
import com.lsmom.core.work.WorkItem;
import com.lsmom.core.work.WorkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 以 Issue 承载闭环的对象（问题本身、不符合项、缺陷）结束时的影响说明：
 * 将结束的对象、同时关闭的关联问题、整改工作数量（已完成 / 未完成）。
 */
public class IssueImpact implements OperationImpactProvider {

    /** 对象说明与关联问题 id（没有关联问题时为 null）。 */
    public record Target(String label, Long issueId) {
    }

    private final Map<String, String> verbs;
    private final String noun;
    private final Function<String, Target> target;
    private final IssueService issues;

    /**
     * @param verbs  Operation 编码 → 动词（关闭 / 驳回 / 取消）
     * @param noun   对象名（问题 / 不符合项 / 缺陷）
     * @param target 按目标 id 取对象说明与关联问题
     */
    public IssueImpact(Map<String, String> verbs, String noun, Function<String, Target> target, IssueService issues) {
        this.verbs = verbs;
        this.noun = noun;
        this.target = target;
        this.issues = issues;
    }

    @Override
    public boolean supports(String operation) {
        return verbs.containsKey(operation);
    }

    @Override
    public List<String> describe(String operation, String targetId) {
        String verb = verbs.get(operation);
        Target t = target.apply(targetId);
        List<String> lines = new ArrayList<>();
        lines.add("将" + verb + " 1 个" + noun + "：" + t.label());
        if (t.issueId() == null) return lines;
        Issue i = issues.get(t.issueId());
        if (!"问题".equals(noun) && "关闭".equals(verb) && i.status() != com.lsmom.core.issue.IssueStatus.CLOSED) lines.add("同时关闭关联问题 " + i.code());
        List<WorkItem> rect = issues.rectifications(i.id());
        if (!rect.isEmpty()) {
            long done = rect.stream().filter(w -> w.status() == WorkStatus.DONE).count();
            long open = rect.stream().filter(w -> !w.status().terminal()).count();
            lines.add("关联整改工作 " + rect.size() + " 项（已完成 " + done + " 项" + (open > 0 ? "，未完成 " + open + " 项" : "") + "）");
        }
        return lines;
    }
}
