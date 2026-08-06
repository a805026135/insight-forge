package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailRule;
import com.insightforge.agent.core.planning.Metric;
import com.insightforge.agent.core.planning.Operator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证影子模式：相同 DSL 决策一致；不同阈值 DSL 决策不一致能被捕获（上线前安全评估）。
 */
@SpringBootTest(classes = RuleEngineTestConfig.class)
@TestPropertySource(properties = "spring.main.web-application-type=none")
class ShadowEngineTest {

    @Autowired
    ShadowEngine shadow;

    @Test
    void identicalPolicyMatches() {
        GuardrailPolicy p = policyWithReallocate(0.2, "v1");
        SalesTick tick = new SalesTick(1L, 1L, 0.1, 50, 1, 0.9, 10, 100, 300, 0); // 触发 reallocate
        var r = shadow.compare(tick, p, p);
        assertTrue(r.matched());
    }

    @Test
    void differentThresholdDetectsMismatch() {
        // 同一活动、不同版本（新阈值=新 DSL 版本，生产里就是不同的 activityId:version 缓存键）。
        // pLow 阈值 0.2：tick stockRatio=0.3 不触发；pHigh 阈值 0.5：tick stockRatio=0.3 触发 → 决策应不一致。
        GuardrailPolicy pLow = policyWithReallocate(0.2, "v1");
        GuardrailPolicy pHigh = policyWithReallocate(0.5, "v2");
        SalesTick tick = new SalesTick(1L, 1L, 0.3, 50, 1, 0.9, 30, 100, 300, 0);
        var r = shadow.compare(tick, pLow, pHigh);
        assertFalse(r.matched());
    }

    private GuardrailPolicy policyWithReallocate(double threshold, String version) {
        GuardrailRule r = new GuardrailRule("r", Metric.STOCK_RATIO, Operator.LT,
                new BigDecimal(String.valueOf(threshold)), Action.REALLOCATE, Map.of("amount", 50));
        return new GuardrailPolicy(1L, version, List.of(r));
    }
}
