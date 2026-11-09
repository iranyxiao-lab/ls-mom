package com.lsmom.core.party;

/** 统一主体。{@code userId} 为空表示不能登录。 */
public record Party(Long id, String code, PartyType type, String name, Long userId, String phone, String orgName, String status) {

    public boolean canLogin() {
        return userId != null && "ACTIVE".equals(status);
    }
}
