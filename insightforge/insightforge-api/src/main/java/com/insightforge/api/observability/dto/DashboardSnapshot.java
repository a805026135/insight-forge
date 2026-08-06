package com.insightforge.api.observability.dto;

/**
 * 大屏总览快照（Phase 10）。
 *
 * 一次请求返回三大块：秒杀实时经营、Agent 离线规划质量、护栏策略版本。
 * 全部由 {@code ObservabilityService} 从已落库的真相源聚合而来（读 persistence 的 Mapper），
 * 不依赖任何外部模块，因此沙箱里也能跑通、能单测。
 *
 * 用 record：不可变、自解释、Jackson 直接序列化。
 */
public record DashboardSnapshot(
        SeckillSummary seckill,
        AgentSummary agent,
        GuardrailSummary guardrail,
        /** 快照生成时间（ISO，前端直接展示） */
        String generatedAt
) {

    /**
     * 秒杀实时经营（来自 activity / activity_sku / orders 表）。
     * - 防超卖的事实在这里用「sold <= totalStock 恒成立」间接佐证（Phase 11 压测再定量）。
     */
    public record SeckillSummary(
            long totalActivities,
            long liveActivities,
            long totalStock,
            long soldStock,
            long totalOrders
    ) {
    }

    /**
     * Agent 离线规划质量（来自 agent_trace 表，对应监测设计文档 L4 系统评估）。
     * correctFailureRate = 正确失败数 / 总运行数；正确失败（ASK/REFUSE/ESCALATE）是**好**信号，
     * 证明 Agent 在该拒绝/该求助时没有硬上——这条红线指标必须能在大屏看到。
     */
    public record AgentSummary(
            long totalRuns,
            long successRuns,
            long correctFailureRuns,
            long failedRuns,
            /** 0~1，正确失败率 */
            double correctFailureRate,
            long totalLatencyMsMax,
            /** 累计成本（美元） */
            double totalCostUsd
    ) {
    }

    /**
     * 护栏策略（来自 guardrail_policy 表，对应 L3 结果评估）。
     * 线上「当前生效版本」有几个活动、共多少条规则、最高风险在哪。
     */
    public record GuardrailSummary(
            long policyRows,
            long activitiesWithPolicy,
            String topRiskLevel
    ) {
    }
}
