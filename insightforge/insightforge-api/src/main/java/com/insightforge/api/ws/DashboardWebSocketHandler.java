package com.insightforge.api.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.api.observability.ObservabilityService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 大屏实时推送处理器（Phase 10）。
 *
 * 前端大屏通过 WS 连上来后，后端立刻推一份最新快照（JSON）。
 * 生产里这里应该由「规则引擎事件总线」驱动（每次控盘/每次 trace 落库就推一次），
 * 教学项目简化为「连上即推一份」，足以演示实时刷新；高频定时推送留给部署环境按需开启。
 *
 * 为什么不用 ScheduledExecutor 在后台狂推：测试上下文也会加载这个 @Component，
 * 后台线程会干扰测试且无意义；连上才推最干净，且不依赖定时器。
 */
@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private final ObservabilityService observabilityService;
    private final ObjectMapper objectMapper;

    public DashboardWebSocketHandler(ObservabilityService observabilityService, ObjectMapper objectMapper) {
        this.observabilityService = observabilityService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 连上即推一份最新快照，前端拿到就能渲染第一帧
        String payload = objectMapper.writeValueAsString(observabilityService.buildSnapshot());
        session.sendMessage(new TextMessage(payload));
    }
}
