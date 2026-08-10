package com.insightforge.agent.core.planning;

import com.insightforge.agent.core.memory.Artifact;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Guardrail Policy Agent（大白话：把四个角色的产物「拼」成一份护栏 DSL）。
 *
 * 它是 Phase 8 的「汇编器」：选品/定价/预热/风控各交来一份产物，
 * 这里把它们翻译成统一的 DSL 规则（REPRICE / REALLOCATE / ALERT）。
 * 注意：它只「翻译」，不「拍板」——译完交给 DslCompiler 编译、IndependentCritic 校验。
 */
@Component
public class GuardrailPolicyAgent {

    public GuardrailPolicy assemble(PlanningContext ctx, Map<Role, Artifact> artifacts) {
        List<GuardrailRule> rules = new ArrayList<>();

        // 定价产物 → REPRICE 规则（Phase 14.1 演示策略：库存水位低于 50% 视为「畅销」即提价，
        // 调价不得低于成本由 DslCompiler 把关）。用 STOCK_RATIO 做触发条件是为了在真实闭环里
        // 让 REPRICE 能被 SalesTickConsumer 计算出的 ratio 驱动——PRICE_GAP 需要价差信号源，
        // 当前消费者未聚合该信号，保持诚实：该变体规则由 ratio 驱动、语义为「畅销提价」。
        Map<String, Object> pm = LocalRoleAgent.asMap(artifacts.get(Role.PRICER));
        BigDecimal repriceTo = bd(pm, "repriceTo");
        BigDecimal costFloor = bd(pm, "costFloor");
        rules.add(new GuardrailRule("r_price",
                Metric.STOCK_RATIO, Operator.LT, new BigDecimal("0.5"),
                Action.REPRICE, Map.of("repriceTo", repriceTo, "costFloor", costFloor)));

        // 预热产物 → REALLOCATE 规则（库存比例过低就调拨，调拨只建新段由 Phase 9 热路径保证）
        Map<String, Object> wm = LocalRoleAgent.asMap(artifacts.get(Role.WARMUP));
        BigDecimal amount = bd(wm, "reallocateAmount");
        rules.add(new GuardrailRule("r_alloc",
                Metric.STOCK_RATIO, Operator.LT, new BigDecimal("0.2"),
                Action.REALLOCATE, Map.of("amount", amount,
                        "targetSegment", wm.getOrDefault("targetSegment", "seg_hot"))));

        // 风控产物 → ALERT 规则（销售速度骤降只告警，不写共享状态）
        rules.add(new GuardrailRule("r_risk",
                Metric.SALES_VELOCITY, Operator.LT, new BigDecimal("5"),
                Action.ALERT, Map.of()));

        return new GuardrailPolicy(ctx.activityId(), "v?", rules);
    }

    private BigDecimal bd(Map<String, Object> m, String k) {
        if (m == null || !m.containsKey(k)) {
            return BigDecimal.ZERO;
        }
        Object v = m.get(k);
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(v));
    }
}
