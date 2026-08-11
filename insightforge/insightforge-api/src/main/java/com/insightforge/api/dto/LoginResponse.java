package com.insightforge.api.dto;

import com.insightforge.api.auth.AuthUser;
import lombok.Data;

/**
 * 登录响应——返回用户身份信息 + JWT Token，前端据此控制菜单/路由并携带鉴权头。
 * role = "user" 对应买家端，"merchant" 对应商家端。
 */
@Data
public class LoginResponse {
    private Long id;
    private String username;
    private String role;
    private String displayName;
    private Long merchantId;
    /** JWT（后续请求放 Authorization: Bearer <token>） */
    private String token;
    /** 令牌过期时间（epoch 毫秒），前端据此自动登出，避免“隔天仍免登录” */
    private Long tokenExpiresAt;

    public static LoginResponse from(AuthUser u) {
        LoginResponse r = new LoginResponse();
        r.setId(u.id());
        r.setUsername(u.username());
        r.setRole(u.role());
        r.setDisplayName(u.displayName());
        r.setMerchantId(u.merchantId());
        return r;
    }
}
