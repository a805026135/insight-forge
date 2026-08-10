package com.insightforge.api.auth;

import com.insightforge.persistence.entity.User;

/**
 * 已认证用户（JWT 载荷 + 登录响应的共同载体）。
 */
public record AuthUser(Long id, String username, String role, String displayName) {

    public static AuthUser from(User u) {
        return new AuthUser(u.getId(), u.getUsername(), u.getRole(), u.getDisplayName());
    }

    public boolean isMerchant() {
        return "merchant".equals(role) || "admin".equals(role);
    }
}
