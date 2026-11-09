package com.lsmom.biz.production;

import java.util.List;
import java.util.Map;

/**
 * 交接班摘要草稿：按当班工作与未关闭异常拼成可读文本。只生成草稿，交班人确认后才发布；
 * 运行期 Agent（LLM）摘要接入后可替换为模型生成，仍须保留引用并经人工确认。
 */
public class HandoverSummarizer {

    public record Summary(String summary, String issueSummary) {
    }

    public Summary summarize(ShiftWindow shift, String fromTeam, List<Map<String, Object>> done, List<Map<String, Object>> unfinished,
                             List<Map<String, Object>> issues) {
        StringBuilder s = new StringBuilder();
        s.append(fromTeam).append(" ").append(shift.label()).append("：完成 ").append(done.size()).append(" 项，未完成 ")
            .append(unfinished.size()).append(" 项。");
        if (!done.isEmpty()) {
            s.append("\n已完成：");
            done.forEach(w -> s.append("\n- ").append(w.get("code")).append(" ").append(w.get("title")));
        }
        if (!unfinished.isEmpty()) {
            s.append("\n未完成（移交接班班组）：");
            unfinished.forEach(w -> {
                s.append("\n- ").append(w.get("code")).append(" ").append(w.get("title")).append("（").append(statusName(w.get("status")));
                if (w.get("progress") != null) s.append("，进度 ").append(new java.math.BigDecimal(String.valueOf(w.get("progress"))).stripTrailingZeros().toPlainString()).append("%");
                if (w.get("block_reason") != null) s.append("，阻塞：").append(w.get("block_reason"));
                s.append("）");
            });
        }
        String is = issues.isEmpty() ? "本班组无未关闭异常。" : "未关闭异常 " + issues.size() + " 项：" + String.join("；",
            issues.stream().map(i -> i.get("code") + " " + i.get("title") + "（" + i.get("severity") + "）").toList());
        return new Summary(s.toString(), is);
    }

    static String statusName(Object st) {
        return switch (String.valueOf(st)) {
            case "DRAFT" -> "草稿";
            case "READY" -> "就绪";
            case "ASSIGNED" -> "已分派";
            case "IN_PROGRESS" -> "进行中";
            case "PENDING_CONFIRM" -> "待确认";
            case "BLOCKED" -> "阻塞";
            case "DONE" -> "已完成";
            default -> String.valueOf(st);
        };
    }
}
