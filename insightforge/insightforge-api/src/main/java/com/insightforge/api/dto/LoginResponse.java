package com.insightforge.api.dto;

import lombok.Data;

/**
 * 登录响应——返回用户身份信息，前端据此控制菜单与路由。
 * role = "user" 对应买家端，"merchant" 对应商家端。
 */
@Data
public class LoginResponse {
    private Long id;
    private String username;
    private String role;
    private String displayName;

    public static LoginResponse from(com.insightforge.persistence.entity.User u) {
        LoginResponse r = new LoginResponse();
        r.setId(u.getId());
        r.setUsername(u.getUsername());
        r.setRole(u.getRole());
        r.setDisplayName(u.getDisplayName());
        return r;
    }
}
