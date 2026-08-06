package com.insightforge.agent.core.planning;

/**
 * 多智能体角色（大白话：这场规划里各有各的活儿）。
 * 也用作 Trace span 的 role 字段，便于在查看器里看清「谁干了什么」。
 */
public enum Role {
    PLANNER,      // 总规划：把目标拆成 DAG
    SELECTOR,     // 选品 Agent：挑哪些 SKU 进活动
    PRICER,       // 定价 Agent：算该卖多少钱（≥成本）
    WARMUP,       // 库存预热 Agent：算该调拨多少
    RISK,         // 风控 Agent：定告警/熔断阈值
    GUARDRAIL,    // Guardrail Policy Agent：把上面产物汇编成 DSL
    ORCHESTRATOR, // 总控：串起所有人，管重试与发布
    CRITIC        // 独立验证器
}
