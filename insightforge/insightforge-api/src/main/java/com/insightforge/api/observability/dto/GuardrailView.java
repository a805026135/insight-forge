package com.insightforge.api.observability.dto;

/**
 * 护栏策略的大屏视图（Phase 10，对应 L3 结果评估 + 风险分级）。
 *
 * 因为 rule-engine 此刻未接入 api 运行时（独立 Worker），这里不直接依赖 DSL 类型，
 * 而是从 guardrail_policy 表的 policy_json 里**确定性地**数出规则条数、推断风险等级：
 *   - 含 REALLOCATE / CROSS 类动作 → 高（写入 / 跨活动预算再分配，设计文档定为高风险）
 *   - 含 REPRICE → 中（查询 / 调价）
 *   - 仅只读类 → 低
 * 这是诚实的「从已落库事实推导」，不是猜。
 */
public record GuardrailView(
        Long activityId,
        String version,
        int ruleCount,
        String topRiskLevel,
        String summary
) {
}
