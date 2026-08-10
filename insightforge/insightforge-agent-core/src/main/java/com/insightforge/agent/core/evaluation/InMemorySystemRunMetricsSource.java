package com.insightforge.agent.core.evaluation;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.math.BigDecimal;

/**
 * 内存确定性系统运行指标源（大白话：预置一份 Agent 运行「安全仪表盘」读数）。
 *
 * 仅当显式开启 insightforge.eval.use-inmemory-metrics=true 时启用（离线调试兜底）；
 * 默认使用 {@link TraceSystemRunMetricsSource} 聚合真实 agent_trace。
 */
@Component
@ConditionalOnProperty(name = "insightforge.eval.use-inmemory-metrics", havingValue = "true", matchIfMissing = false)
public class InMemorySystemRunMetricsSource implements SystemRunMetricsSource {

    @Override
    public SystemRunMetrics metrics() {
        return new SystemRunMetrics(
                820L,                       // successes
                42L,                        // correctFailures（主动拒绝/求助，计入正确失败率）
                3L,                         // failures（崩溃）
                850L,                       // maxLatencyMs
                new BigDecimal("1.27")      // totalCostUsd
        );
    }
}
