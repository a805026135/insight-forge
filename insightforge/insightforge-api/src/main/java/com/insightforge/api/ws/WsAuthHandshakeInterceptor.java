package com.insightforge.api.ws;

import com.insightforge.api.auth.AuthService;
import com.insightforge.api.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * WebSocket 握手鉴权（Phase 14.4 安全收尾）。
 *
 * 大屏推送的是全量经营快照，不能让匿名连接拿到。握手时校验
 * {@code ?token=<JWT>}（前端用 localStorage 里的 token 拼接），
 * 且要求 role=merchant/admin；失败直接拒绝握手（403）。
 */
@Component
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    private final AuthService authService;

    public WsAuthHandshakeInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = request.getURI().getQuery() == null ? null
                : queryParam(request.getURI().getQuery(), "token");
        Optional<AuthUser> user = token == null || token.isBlank()
                ? Optional.empty() : authService.parse(token);
        if (user.isEmpty() || !user.get().isMerchant()) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        attributes.put("authUser", user.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需后处理
    }

    private String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
