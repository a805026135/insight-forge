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

    public WebSocketConfig(DashboardWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/dashboard")
                .setAllowedOriginPatterns("*");
    }
}
