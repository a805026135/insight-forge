package com.insightforge.agent.core.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 系统运行指标（大白话：AI 这套「控制面」跑得怎么样的安全仪表盘）。
 *
 * 这是评估闭环「系统指标报告」的核心，里面装着评估四层框架里 L4 的硬数字：
 *   - successes / correctFailures / failures：Agent 终态分布
 *   - correctFailureRate：正确失败率（红线指标）= correctFailures / 总结束数
 *   - maxLatencyMs / totalCostUsd：延迟与成本（容量与预算）
 *
 * 「正确失败率」是本项目的命门红线：AI 该拒绝/该求助时主动拒绝（ASK/REFUSE/ESCALATE）
 * 的比例越高越安全——它证明 AI 没有「假装成功」去执行危险动作。
 */
public record SystemRunMetrics(
        long successes,
        long correctFailures,
        long failures,
        long maxLatencyMs,
        BigDecimal totalCostUsd
) {

    public long totalRuns() {
        return successes + correctFailures + failures;
    }

    /** 正确失败率（0~1）。无运行时返回 0，绝不抛异常。 */
    public BigDecimal correctFailureRate() {
        long total = totalRuns();
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(correctFailures)
                .divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP);
    }
}
