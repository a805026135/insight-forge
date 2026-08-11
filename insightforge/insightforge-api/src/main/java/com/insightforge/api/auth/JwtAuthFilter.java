package com.insightforge.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.common.result.ApiResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * JWT 鉴权过滤器（Phase 14.3）。
 *
 * 规则（透明、可读）：
 *   - 公开：/api/login、/actuator/**（健康检查/监控）、非 /api 路径；
 *   - 其余 /api/** 必须携带合法 Bearer Token，否则 401；
 *   - 商家专属端点（活动状态机/创建、Agent 规划、HITL 审批、预热、大屏管理数据）要求 role=merchant，否则 403；
 *   - 买家页面所需的 /api/dashboard/skus、/api/dashboard/explain 任何登录用户可访问。
 *
 * 登录用户放入 {@link AuthContextHolder}，Controller 据此强制以登录身份操作。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 公开路径
        if ("/api/login".equals(path) || "/api/register".equals(path)
                || path.startsWith("/actuator") || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 其余 /api/** 必须带 token
        String token = bearer(request);
        Optional<AuthUser> user = token == null ? Optional.empty() : authService.parse(token);
        if (user.isEmpty()) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录或登录已过期");
            return;
        }

        AuthContextHolder.set(user.get());
        try {
            if (!allowed(request, user.get())) {
                write(response, HttpServletResponse.SC_FORBIDDEN, "无权限执行该操作");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            AuthContextHolder.clear();
        }
    }

    /** 商家专属端点判定（买家/普通用户 403）。 */
    private boolean allowed(HttpServletRequest request, AuthUser user) {
        if (user.isMerchant()) {
            return true;
        }
        String path = request.getRequestURI();
        // 买家可访问：秒杀（不含 admin 管理操作）、订单、SKU 列表、解释接口
        if ((path.startsWith("/api/seckill") && !path.startsWith("/api/seckill/admin"))
                || path.startsWith("/api/order")
                || path.startsWith("/api/dashboard/skus")
                || path.startsWith("/api/dashboard/explain")
                || path.startsWith("/api/catalog")
                || path.startsWith("/api/user")
                || path.startsWith("/api/buyer")) {
            return true;
        }
        // 活动列表：任何登录用户可读（买家秒杀页要展示活动）；创建/状态机仍为商家专属
        if (path.startsWith("/api/activity") && "GET".equals(request.getMethod())) {
            return true;
        }
        // 其余（活动管理/状态机、Agent 规划、大屏管理数据、HITL 审批、预热）为商家专属
        return false;
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResult.fail(status, message));
    }
}
