package com.insightforge.agent.core.planning;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DSL 静态编译器单元测试（Fail-Close 门禁，无需 Spring 上下文，纯算法）。
 */
class DslCompilerTest {

    private final DslCompiler compiler = new DslCompiler();

    private GuardrailRule repriceRule() {
        return new GuardrailRule("r_price", Metric.PRICE_GAP, Operator.GT, new BigDecimal("0.15"),
                Action.REPRICE, Map.of("repriceTo", new BigDecimal("6299"), "costFloor", new BigDecimal("5999")));
    }

    private GuardrailRule reallocRule() {
        return new GuardrailRule("r_alloc", Metric.STOCK_RATIO, Operator.LT, new BigDecimal("0.2"),
                Action.REALLOCATE, Map.of("amount", new BigDecimal("50")));
    }

    private GuardrailPolicy valid() {
        return new GuardrailPolicy(1L, "v1", List.of(repriceRule(), reallocRule()));
    }

    @Test
    void validPolicyCompiles() {
        assertTrue(compiler.compile(valid()).valid());
    }

    @Test
    void emptyRulesFails() {
        DslCompileResult r = compiler.compile(new GuardrailPolicy(1L, "v1", List.of()));
        assertFalse(r.valid());
    }

    @Test
    void unknownMetricFails() {
        GuardrailRule bad = new GuardrailRule("r1", Metric.INVALID, Operator.GT, new BigDecimal("0.1"),
                Action.ALERT, Map.of());
        assertFalse(compiler.compile(new GuardrailPolicy(1L, "v1", List.of(bad))).valid());
    }

    @Test
    void unknownActionFails() {
        GuardrailRule bad = new GuardrailRule("r1", Metric.SALES_VELOCITY, Operator.LT, new BigDecimal("5"),
                Action.INVALID, Map.of());
        assertFalse(compiler.compile(new GuardrailPolicy(1L, "v1", List.of(bad))).valid());
    }

    @Test
    void negativeThresholdFails() {
        GuardrailRule bad = new GuardrailRule("r1", Metric.STOCK_RATIO, Operator.LT, new BigDecimal("-0.1"),
                Action.ALERT, Map.of());
        assertFalse(compiler.compile(new GuardrailPolicy(1L, "v1", List.of(bad))).valid());
    }

    @Test
    void priceBelowCostFails() {
        // 调价护栏：repriceTo 必须 ≥ costFloor
        GuardrailRule bad = new GuardrailRule("r_price", Metric.PRICE_GAP, Operator.GT, new BigDecimal("0.15"),
                Action.REPRICE, Map.of("repriceTo", new BigDecimal("5000"), "costFloor", new BigDecimal("5999")));
        assertFalse(compiler.compile(new GuardrailPolicy(1L, "v1", List.of(bad))).valid());
    }

    @Test
    void negativeReallocationFails() {
        // 库存护栏：REALLOCATE 的 amount 必须 ≥ 0
        GuardrailRule bad = new GuardrailRule("r_alloc", Metric.STOCK_RATIO, Operator.LT, new BigDecimal("0.2"),
                Action.REALLOCATE, Map.of("amount", new BigDecimal("-50")));
        assertFalse(compiler.compile(new GuardrailPolicy(1L, "v1", List.of(bad))).valid());
    }

    @Test
    void duplicateRuleIdFails() {
        assertFalse(compiler.compile(new GuardrailPolicy(1L, "v1",
                List.of(repriceRule(), repriceRule()))).valid());
    }
}
