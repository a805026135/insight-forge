package com.insightforge.api.dto;

/**
 * 触发 Agent 离线规划的请求 DTO。
 *
 * activityId：要为哪个活动做规划（Agent 会读该活动的商品/库存/目标 GMV 等上下文）。
 * goal：自然语言目标，如「为活动 1 制定双11 控盘预案，目标 GMV 500 万」。
 */
public record AgentPlanRequest(
        Long activityId,
        String goal
) {
}
