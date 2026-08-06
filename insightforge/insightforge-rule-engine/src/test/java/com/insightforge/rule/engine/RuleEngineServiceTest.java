package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailRule;
import com.insightforge.agent.core.planning.Metric;
import com.insightforge.agent.core.planning.Operator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证规则引擎端到端：DSL → 翻译 → Drools 编译 → 吞 tick → 吐指令（数据面确定性执行）。
 */
@SpringBootTest(classes = RuleEngineTestConfig.class)
@TestPropertySource(properties = "spring.main.web-application-type=none")
class RuleEngineServiceTest {

    @Autowired
    RuleEngineService engine;

    @Autowired
    InMemoryPolicySource source;

    private GuardrailPolicy policy;

    @BeforeEach
    void installPolicy() {
        GuardrailRule reallocate = new GuardrailRule("r_low_stock", Metric.STOCK_RATIO, Operator.LT,
                new BigDecimal("0.2"), Action.REALLOCATE, Map.of("amount", 50));
        GuardrailRule reprice = new GuardrailRule("r_price", Metric.PRICE_GAP, Operator.GT,
                new BigDecimal("5"), Action.REPRICE, Map.of("repriceTo", 9.9, "costFloor", 8.0));
        GuardrailRule alert = new GuardrailRule("r_slow", Metric.SALES_VELOCITY, Operator.LT,
                new BigDecimal("10"), Action.ALERT, Map.of("reason", "slow"));
        policy = new GuardrailPolicy(100L, "v1", List.of(reallocate, reprice, alert));
        source.install(100L, policy);
    }

    @Test
    void evaluateProducesAllThreeInstructions() {
        // stockRatio=0.1<0.2 → reallocate; priceGap=6.0>5 → reprice; salesVelocity=5.0<10 → alert
        SalesTick tick = new SalesTick(100L, 1L, 0.1, 5.0, 6.0, 0.3, 10, 100, 300, System.currentTimeMillis());
        List<ControlInstruction> out = engine.evaluate(100L, tick);

        assertEquals(3, out.size());
        assertTrue(out.stream().anyMatch(c -> c.getAction() == Action.REALLOCATE));
        assertTrue(out.stream().anyMatch(c -> c.getAction() == Action.REPRICE));
        assertTrue(out.stream().anyMatch(c -> c.getAction() == Action.ALERT));
    }

    @Test
    void noPolicyReturnsEmpty() {
        SalesTick tick = new SalesTick(999L, 1L, 0.9, 50, 1, 0.9, 90, 100, 600, 0);
        assertTrue(engine.evaluate(999L, tick).isEmpty());
    }

    @Test
    void lowStockTriggersReallocateOnly() {
        // stockRatio=0.1<0.2 → reallocate; priceGap=1.0<5 → 不调价; salesVelocity=50>10 → 不告警
        SalesTick tick = new SalesTick(100L, 1L, 0.1, 50.0, 1.0, 0.9, 10, 100, 300, 0);
        List<ControlInstruction> out = engine.evaluate(100L, tick);

        assertEquals(1, out.size());
        assertEquals(Action.REALLOCATE, out.get(0).getAction());
    }
}
