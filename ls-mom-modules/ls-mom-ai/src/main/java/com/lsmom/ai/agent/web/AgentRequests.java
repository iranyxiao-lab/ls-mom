package com.lsmom.ai.agent.web;

import com.lsmom.action.api.ActorContext;
import com.lsmom.action.web.ActorResolver;
import com.lsmom.ai.agent.AgentActors;
import com.lsmom.ai.agent.AgentRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 从请求中解析 Agent 调用主体：真人登录会话（OBO）＋ {@code X-Agent-Key}。 */
@Component
public class AgentRequests {

    public static final String HEADER = "X-Agent-Key";

    public record Resolved(AgentRegistry.Principal principal, ActorContext actor) {
    }

    private final AgentRegistry registry;
    private final ActorResolver actors;

    public AgentRequests(AgentRegistry registry, ActorResolver actors) {
        this.registry = registry;
        this.actors = actors;
    }

    public Resolved resolve(HttpServletRequest req) {
        AgentRegistry.Principal p = registry.authenticate(req.getHeader(HEADER));
        return new Resolved(p, AgentActors.compose(actors.current(), p.identity()));
    }
}
