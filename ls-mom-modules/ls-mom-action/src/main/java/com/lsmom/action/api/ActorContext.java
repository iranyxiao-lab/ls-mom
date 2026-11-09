package com.lsmom.action.api;

import com.lsmom.core.org.DataScope;

import java.util.Set;

/**
 * 调用主体。人与 Agent 走同一入口：Agent 以自身身份（actorParty）代表某个人（onBehalfOf）执行，
 * 有效权限为两者交集（由调用方在构造时计算好 permissions 与 dataScope）。
 */
public record ActorContext(ActorType type, Long userId, Long actorParty, Long onBehalfOf, boolean superAdmin,
                           Set<String> permissions, DataScope dataScope, String displayName) {

    public enum ActorType { HUMAN, AGENT, SERVICE, SYSTEM }

    public static ActorContext system(String name) {
        return new ActorContext(ActorType.SYSTEM, null, null, null, true, Set.of(), DataScope.unrestricted(), name);
    }

    public boolean hasPermission(String perm) {
        if (perm == null || perm.isBlank() || superAdmin) return true;
        return permissions.contains(perm) || permissions.contains("*:*:*");
    }

    /** 业务上"谁做的"：代理执行时为被代理人。 */
    public Long effectiveParty() {
        return onBehalfOf != null ? onBehalfOf : actorParty;
    }
}
