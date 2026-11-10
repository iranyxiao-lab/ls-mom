package com.lsmom.ai.agent;

import com.lsmom.action.api.ActorContext;
import com.lsmom.core.error.LsBizException;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Agent 代真人行动时的调用主体（OBO 双主体）：actorParty 为 Agent 主体，onBehalfOf 为真人主体。
 * 有效功能权限 = 真人权限 ∩ Agent 服务身份权限（白名单与黑名单由网关按暴露等级与禁用清单执行）；数据域 = 真人数据域。
 */
public final class AgentActors {

    public static final String ALL = "*:*:*";

    private AgentActors() {
    }

    public static ActorContext compose(ActorContext human, AgentRegistry.Identity agent) {
        if (human == null || human.type() != ActorContext.ActorType.HUMAN || human.actorParty() == null) {
            throw new LsBizException("AGENT_OBO_REQUIRED", "Agent 须代表一位已登录的真人调用");
        }
        return new ActorContext(ActorContext.ActorType.AGENT, human.userId(), agent.partyId(), human.actorParty(), false,
            effective(human, agent.permissions()), human.dataScope(), agent.name() + "（代 " + human.displayName() + "）");
    }

    static Set<String> effective(ActorContext human, Set<String> agentPerms) {
        boolean humanAll = human.superAdmin() || human.permissions().contains(ALL);
        boolean agentAll = agentPerms.contains(ALL);
        if (humanAll && agentAll) return Set.of(ALL);
        if (humanAll) return Set.copyOf(agentPerms);
        if (agentAll) return Set.copyOf(human.permissions());
        Set<String> s = new LinkedHashSet<>(human.permissions());
        s.retainAll(agentPerms);
        return Set.copyOf(s);
    }
}
