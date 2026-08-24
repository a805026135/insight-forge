package com.insightforge.agent.core.planning;

import java.util.List;

/** 一次规划的可解释路由结果，会随 Trace 保存用于审计和评测。 */
public record RoutingDecision(
        Mode mode,
        List<Role> roles,
        int complexityScore,
        String riskLevel,
        List<String> reasons
) {
    public RoutingDecision {
        roles = List.copyOf(roles);
        reasons = List.copyOf(reasons);
    }

    public enum Mode {
        FOCUSED,
        FULL
    }
}
