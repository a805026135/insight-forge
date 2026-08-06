package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailRule;
import com.insightforge.agent.core.planning.Metric;
import com.insightforge.agent.core.planning.Operator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 DSL→DRL 翻译器的正确性（核心亮点：翻译出的 DRL 必须能被 Drools 编译执行）。
 */
class DslToDroolsTranslatorTest {

    private final DslToDroolsTranslator translator = new DslToDroolsTranslator();

    @Test
    void translatesReallocateRuleToDrl() {
        GuardrailRule r = new GuardrailRule("r_low_stock", Metric.STOCK_RATIO, Operator.LT,
                new BigDecimal("0.2"), Action.REALLOCATE, Map.of("amount", 50));
        GuardrailPolicy p = new GuardrailPolicy(1L, "v1", List.of(r));
        String drl = translator.translate(p);

        // package 与 KieFileSystem 写入的 rules/ 目录一致（消除 Drools 包名/目录不匹配告警）
        assertTrue(drl.contains("package rules"));
        assertTrue(drl.contains("import com.insightforge.agent.core.planning.Action"));
        assertTrue(drl.contains("global java.util.List instructions"));
        assertTrue(drl.contains("SalesTick( getStockRatio() < 0.2d )"), drl);
        assertTrue(drl.contains("Action.REALLOCATE"));
        assertTrue(drl.contains("RiskLevel.MEDIUM"));
        assertTrue(drl.contains("\"amount\", 50"), drl);
    }

    @Test
    void translatesRepriceRuleWithCostFloor() {
        GuardrailRule r = new GuardrailRule("r_price", Metric.PRICE_GAP, Operator.GT,
                new BigDecimal("5"), Action.REPRICE, Map.of("repriceTo", 9.9, "costFloor", 8.0));
        GuardrailPolicy p = new GuardrailPolicy(1L, "v1", List.of(r));
        String drl = translator.translate(p);

        assertTrue(drl.contains("SalesTick( getPriceGap() > 5d )"), drl);
        assertTrue(drl.contains("\"repriceTo\", 9.9d"), drl);
        assertTrue(drl.contains("\"costFloor\", 8.0d"), drl);
    }

    @Test
    void translatesAlertRuleToLowRisk() {
        GuardrailRule r = new GuardrailRule("r_alert", Metric.SALES_VELOCITY, Operator.LT,
                new BigDecimal("10"), Action.ALERT, Map.of("reason", "slow"));
        GuardrailPolicy p = new GuardrailPolicy(1L, "v1", List.of(r));
        String drl = translator.translate(p);

        assertTrue(drl.contains("Action.ALERT"));
        assertTrue(drl.contains("RiskLevel.LOW"));
    }
}
