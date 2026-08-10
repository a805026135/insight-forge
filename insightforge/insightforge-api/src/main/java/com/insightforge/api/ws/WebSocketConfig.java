package com.insightforge.api.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册（Phase 10）。
 *
 * 把 {@link DashboardWebSocketHandler} 挂到 {@code /ws/dashboard}。
 * 大屏前端连这个端点拿实时快照。allowedOriginPatterns("*") 方便本地开发跨端口（Vite 5173 → 8080）；
 * 生产应收紧为前端域名。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DashboardWebSocketHandler handler;
    private final WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor;

    public WebSocketConfig(DashboardWebSocketHandler handler,
                           WsAuthHandshakeInterceptor wsAuthHandshakeInterceptor) {
        this.handler = handler;
        this.wsAuthHandshakeInterceptor = wsAuthHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/dashboard")
                .addInterceptors(wsAuthHandshakeInterceptor)
                // Phase 14.4：不再放开任意 Origin，只允许本地开发/部署来源
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }
}
