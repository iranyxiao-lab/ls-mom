package com.lsmom.action.gateway;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.api.AgentCall;
import com.lsmom.action.api.Exposure;
import com.lsmom.action.registry.OperationDefinition;
import com.lsmom.core.error.LsBizException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Agent 调用的硬约束（在 Operation 网关内执行，任何入口都绕不过）：
 * <ul>
 *   <li>永不暴露清单（{@code agent-denylist.txt}）命中即拒绝；</li>
 *   <li>暴露等级：NONE / DENY 拒绝；SIMULATE 只能模拟；APPROVAL_REQUIRED 必须带已核验的审批单；</li>
 *   <li>DRAFT 等级的 Operation 由 Agent 执行后，结果必须是草稿状态。</li>
 * </ul>
 */
public final class AgentPolicy {

    public static final String RESOURCE = "agent-denylist.txt";
    public static final Set<Exposure> TOOL_LEVELS = Set.of(Exposure.QUERY, Exposure.DRAFT, Exposure.SIMULATE, Exposure.APPROVAL_REQUIRED);

    public record Entry(String category, String pattern) {
        boolean matches(OperationDefinition op, Collection<String> guards) {
            if (pattern.startsWith("guard:")) return guards.contains(pattern.substring(6));
            if (pattern.startsWith("perm:")) return op.permission() != null && glob(pattern.substring(5)).matcher(op.permission()).matches();
            return glob(pattern).matcher(op.code()).matches();
        }
    }

    private final List<Entry> entries;

    public AgentPolicy(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** 类路径中的标准清单。 */
    public static AgentPolicy standard() {
        try (InputStream in = AgentPolicy.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("缺少 " + RESOURCE);
            return new AgentPolicy(parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String[] p = l.split("\\s+");
            if (p.length != 2) throw new IllegalStateException("Agent 禁用清单格式错误：" + l);
            out.add(new Entry(p[0], p[1]));
        }
        return out;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 命中的永不暴露类别。 */
    public Optional<String> denied(OperationDefinition op, Collection<String> guards) {
        return entries.stream().filter(e -> e.matches(op, guards)).map(Entry::category).findFirst();
    }

    /** 是否作为 Agent 工具发布（暴露等级在白名单内且未命中禁用清单）。 */
    public boolean isTool(OperationDefinition op, Collection<String> guards) {
        return TOOL_LEVELS.contains(op.exposure()) && denied(op, guards).isEmpty() && "PUBLISHED".equals(op.status());
    }

    /** 网关执行前检查。 */
    void check(OperationDefinition op, boolean simulate, Collection<String> guards, ActorContext actor) {
        if (actor.type() != ActorContext.ActorType.AGENT) return;
        Optional<String> deny = denied(op, guards);
        if (deny.isPresent()) {
            throw new LsBizException("AGENT_TOOL_DENIED", "「" + op.name() + "」属于永不向 Agent 暴露的能力（" + label(deny.get()) + "），须由真人执行",
                Map.of("operation", op.code(), "category", deny.get()));
        }
        switch (op.exposure()) {
            case NONE, DENY -> throw new LsBizException("AGENT_TOOL_DENIED", "「" + op.name() + "」未向 Agent 暴露，须由真人执行",
                Map.of("operation", op.code(), "exposure", op.exposure().name()));
            case SIMULATE -> {
                if (!simulate) {
                    throw new LsBizException("AGENT_SIMULATE_ONLY", "Agent 只能模拟执行「" + op.name() + "」，须由真人正式执行",
                        Map.of("operation", op.code()));
                }
            }
            case APPROVAL_REQUIRED -> {
                AgentCall call = AgentCall.current();
                if (!simulate && (call == null || call.approvalId() == null)) {
                    throw new LsBizException("AGENT_APPROVAL_REQUIRED", "「" + op.name() + "」须经真人批准后由 Agent 执行",
                        Map.of("operation", op.code()));
                }
            }
            default -> {
            }
        }
    }

    /** DRAFT 等级：Agent 执行结果必须是草稿。 */
    void checkResult(OperationDefinition op, String status, ActorContext actor) {
        if (actor.type() != ActorContext.ActorType.AGENT || op.exposure() != Exposure.DRAFT || status == null) return;
        if (!"DRAFT".equals(status)) {
            throw new LsBizException("AGENT_DRAFT_REQUIRED", "Agent 只能产生草稿，「" + op.name() + "」的结果状态为 " + status,
                Map.of("operation", op.code(), "status", status));
        }
    }

    public static String label(String category) {
        return switch (category) {
            case "APPROVAL" -> "代审批";
            case "DUAL_SIGN" -> "双签";
            case "QUALITY_FINAL" -> "质量终判";
            case "GENERIC_DELETE" -> "通用删除";
            case "ARBITRARY_INVOKE" -> "任意方法调用";
            case "DCS_WRITE" -> "DCS 写入";
            default -> category;
        };
    }

    static Pattern glob(String g) {
        StringBuilder b = new StringBuilder();
        for (char c : g.toCharArray()) b.append(c == '*' ? ".*" : Pattern.quote(String.valueOf(c)));
        return Pattern.compile(b.toString());
    }
}
