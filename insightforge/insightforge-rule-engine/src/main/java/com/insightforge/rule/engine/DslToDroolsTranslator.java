package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import org.springframework.stereotype.Component;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailRule;
import com.insightforge.agent.core.planning.Metric;
import com.insightforge.agent.core.planning.Operator;

import java.math.BigDecimal;
import java.util.Map;

/**
 * DSL→Drools 翻译器（核心亮点：把 Phase 8 的「控盘说明书」翻译成工业级规则引擎能跑的 DRL）。
 *
 * 大白话：Agent 写的是结构化 DSL（"库存比例&lt;0.2 就调拨50件"），Drools 看不懂 DSL，
 * 只看得懂自己的 DRL 文本（"when ... then ..."）。本类就是「翻译官」：
 * 每条 GuardrailRule → 一条 Drools rule——左边(when)是条件，右边(then)是开出 ControlInstruction 工单。
 *
 * 为什么值得做：DRL 是业界标准规则语言，翻译出来既能用 Drools 高效执行，也能直接拿去审计 /
 * 对接其它规则平台——证明我们的 Agent 策略不是玩具，而是能落到生产级规则引擎的。
 */
@Component
public class DslToDroolsTranslator {

    /** 把整份 DSL 翻译成完整 DRL 文本（可喂给 {@link DroolsRuleCompiler} 编译）。 */
    public String translate(GuardrailPolicy policy) {
        StringBuilder sb = new StringBuilder();
        // 注意：package 必须与写入的文件夹名一致（KieFileSystem 写到了 rules/ 目录下），否则 Drools 告警。
        sb.append("package rules\n");
        sb.append("import com.insightforge.rule.engine.SalesTick\n");
        sb.append("import com.insightforge.rule.engine.ControlInstruction\n");
        sb.append("import com.insightforge.rule.engine.RiskLevel\n");
        sb.append("import com.insightforge.agent.core.planning.Action\n");
        sb.append("global java.util.List instructions\n\n");

        for (GuardrailRule r : policy.rules()) {
            sb.append(translateRule(r));
        }
        return sb.toString();
    }

    private String translateRule(GuardrailRule r) {
        String metricGetter = metricGetter(r.metric());
        String opSymbol = operatorSymbol(r.operator());
        BigDecimal threshold = r.threshold();
        String lhs = String.format("$t : SalesTick( %s %s %sd )",
                metricGetter, opSymbol, threshold.toString());

        String rhs = translateRhs(r);
        return String.format("rule \"%s\"\nwhen\n  %s\nthen\n  %s\nend\n\n",
                sanitizeRuleName(r.id()), lhs, rhs);
    }

    private String translateRhs(GuardrailRule r) {
        Map<String, Object> p = r.params();
        Action action = r.action();
        if (action == Action.REPRICE) {
            double repriceTo = num(p, "repriceTo");
            double costFloor = num(p, "costFloor");
            // 透传 costFloor，供回测/护栏校验兜底（repriceTo 本应 ≥ costFloor，由 Phase 8 Fail-Close 保证）
            return String.format(
                    "instructions.add(new ControlInstruction($t.getActivityId(), $t.getSkuId(), Action.REPRICE, "
                            + "java.util.Map.of(\"repriceTo\", %sd, \"costFloor\", %sd), RiskLevel.MEDIUM, \"dsl\"));",
                    repriceTo, costFloor);
        }
        if (action == Action.REALLOCATE) {
            int amount = (int) num(p, "amount");
            boolean cross = Boolean.TRUE.equals(p.get("crossActivity"));
            // 跨活动再分配 → 高风险（须人工审批，留 HITL 接口）；否则中风险
            String risk = cross ? "RiskLevel.HIGH" : "RiskLevel.MEDIUM";
            return String.format(
                    "instructions.add(new ControlInstruction($t.getActivityId(), $t.getSkuId(), Action.REALLOCATE, "
                            + "java.util.Map.of(\"amount\", %d), %s, \"dsl\"));",
                    amount, risk);
        }
        // 默认 ALERT：只告警，不写共享状态（最低风险）
        String reason = r.id() == null ? "alert" : r.id().replace("\"", "'").replace("\\", "/");
        return String.format(
                "instructions.add(new ControlInstruction($t.getActivityId(), $t.getSkuId(), Action.ALERT, "
                        + "java.util.Map.of(\"reason\", \"%s\"), RiskLevel.LOW, \"dsl\"));",
                reason);
    }

    private double num(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception e) {
                return 0d;
            }
        }
        return 0d;
    }

    private String metricGetter(Metric m) {
        return switch (m) {
            case STOCK_RATIO -> "getStockRatio()";
            case SALES_VELOCITY -> "getSalesVelocity()";
            case PRICE_GAP -> "getPriceGap()";
            case CONVERSION_RATE -> "getConversionRate()";
            default -> "getStockRatio()";
        };
    }

    private String operatorSymbol(Operator o) {
        return switch (o) {
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case EQ -> "==";
            default -> "<";
        };
    }

    private String sanitizeRuleName(String id) {
        if (id == null || id.isBlank()) {
            return "rule_unnamed";
        }
        // DRL rule 名必须是合法标识符（去掉空格/特殊字符）
        return "rule_" + id.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
