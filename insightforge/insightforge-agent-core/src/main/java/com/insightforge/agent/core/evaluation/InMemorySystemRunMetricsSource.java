package com.insightforge.agent.core.evaluation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 内存确定性系统运行指标源（大白话：预置一份 Agent 运行「安全仪表盘」读数）。
 *
 * 种子是一个「健康且诚实」的分布：绝大多数成功，少量正确失败（主动拒绝不合规），
 * 极少崩溃。正确失败率 = 42 / (820+42+3) ≈ 4.86%，落在安全区间。
 * 生产环境替换为读 agent_trace 聚合（见 SystemRunMetricsSource 注释）。
 */
@Component
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
