package com.insightforge.api.controller;

import com.insightforge.api.dto.LoginRequest;
import com.insightforge.api.dto.LoginResponse;
import com.insightforge.api.dto.RegisterRequest;
import com.insightforge.api.auth.AuthService;
import com.insightforge.api.auth.AuthUser;
import com.insightforge.common.result.ApiResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录入口（Phase 14.3：BCrypt 密码校验 + JWT 签发）。
 *
 * POST /api/login  用户名+密码 → 用户身份（id/role/displayName）+ JWT
 *
 * 前端根据返回的 role 字段分流：role="user" 走买家端，"merchant" 走商家端。
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    private final AuthService authService;

    public LoginController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@RequestBody LoginRequest req) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            return ApiResult.fail(400, "用户名不能为空");
        }
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            return ApiResult.fail(400, "密码不能为空");
        }

        var authUser = authService.authenticate(req.getUsername(), req.getPassword());
        if (authUser.isEmpty()) {
            return ApiResult.fail(401, "用户名或密码错误");
        }

        AuthUser user = authUser.get();
        LoginResponse resp = LoginResponse.from(user);
        long[] expiresAt = new long[1];
        resp.setToken(authService.issueToken(user, expiresAt));
        resp.setTokenExpiresAt(expiresAt[0]);
        return ApiResult.ok(resp);
    }

    /**
     * POST /api/register  用户名 + 密码 + 昵称 → 创建买家账号并直接签发 JWT
     */
    @PostMapping("/register")
    public ApiResult<LoginResponse> register(@RequestBody RegisterRequest req) {
        try {
            var user = authService.register(req.getUsername(), req.getPassword(), req.getDisplayName());
            LoginResponse resp = LoginResponse.from(user);
            long[] expiresAt = new long[1];
            resp.setToken(authService.issueToken(user, expiresAt));
            resp.setTokenExpiresAt(expiresAt[0]);
            return ApiResult.ok(resp);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }
}
