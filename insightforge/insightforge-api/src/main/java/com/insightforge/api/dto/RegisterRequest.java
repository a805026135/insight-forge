package com.insightforge.api.dto;

import lombok.Data;

/**
 * 注册请求体——用户名 + 密码 + 昵称（可选）。
 * 注册只开放买家身份（role=user），商家账号由运营预置，防止越权注册。
 */
@Data
public class RegisterRequest {
    private String username;
    private String password;
    private String displayName;
}
