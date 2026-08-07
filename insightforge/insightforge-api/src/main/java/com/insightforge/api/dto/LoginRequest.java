package com.insightforge.api.dto;

import lombok.Data;

/**
 * 登录请求体——用户名 + 密码。
 * Local profile 下为明文密码（开发环境简化，不依赖 Spring Security）。
 */
@Data
public class LoginRequest {
    private String username;
    private String password;
}
