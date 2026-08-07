package com.insightforge.api.controller;

import com.insightforge.api.dto.LoginRequest;
import com.insightforge.api.dto.LoginResponse;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.mapper.UserMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录入口（local 开发简化版：明文密码比对，不引入 Spring Security）。
 *
 * POST /api/login  用户名+密码 → 用户身份（id/role/displayName）
 *
 * 前端根据返回的 role 字段分流：role="user" 走买家端，"merchant" 走商家端。
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    private final UserMapper userMapper;

    public LoginController(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @PostMapping("/login")
    public ApiResult<LoginResponse> login(@RequestBody LoginRequest req) {
        if (req.getUsername() == null || req.getUsername().isBlank()) {
            return ApiResult.fail(400, "用户名不能为空");
        }
        if (req.getPassword() == null || req.getPassword().isBlank()) {
            return ApiResult.fail(400, "密码不能为空");
        }

        var user = userMapper.findByUsername(req.getUsername());
        if (user == null) {
            return ApiResult.fail(401, "用户名或密码错误");
        }
        if (!req.getPassword().equals(user.getPassword())) {
            return ApiResult.fail(401, "用户名或密码错误");
        }

        return ApiResult.ok(LoginResponse.from(user));
    }
}
