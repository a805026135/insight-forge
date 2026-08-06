package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailRule;
import com.insightforge.agent.core.planning.Metric;
import com.insightforge.agent.core.planning.Operator;
import com.insightforge.rule.engine.BacktestHarness.BacktestReport;
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
 * 验证回测骨架：历史重放，断言 oversell 风险=0（违规指令）且 SLA 达标（决策耗时 < 50ms）。
 */
@SpringBootTest(classes = RuleEngineTestConfig.class)
@TestPropertySource(properties = "spring.main.web-application-type=none")
class BacktestHarnessTest {

    @Autowired
    BacktestHarness backtest;

    @Test
    void historyReplayOversellFreeAndSlaMet() {
        GuardrailPolicy policy = validPolicy();
        List<SalesTick> history = List.of(
                new SalesTick(1L, 1L, 0.1, 50, 6, 0.9, 10, 100, 300, 0), // reallocate + reprice
                new SalesTick(1L, 1L, 0.9, 50, 1, 0.9, 90, 100, 600, 0), // 无触发
                new SalesTick(1L, 1L, 0.15, 5, 7, 0.3, 15, 100, 200, 0)  // reallocate + reprice + alert
        );
        BacktestReport report = backtest.run(policy, history);

        assertTrue(report.oversellFree(), "回放期间不应产生违规指令（超卖风险=0）");
        assertTrue(report.slaMet(), "每条决策耗时应在 SLA(50ms) 内");
        assertTrue(report.passed());
        assertEquals(3, report.tickCount());
    }

    private GuardrailPolicy validPolicy() {
        GuardrailRule reallocate = new GuardrailRule("r_low", Metric.STOCK_RATIO, Operator.LT,
                new BigDecimal("0.2"), Action.REALLOCATE, Map.of("amount", 50));
        GuardrailRule reprice = new GuardrailRule("r_price", Metric.PRICE_GAP, Operator.GT,
                new BigDecimal("5"), Action.REPRICE, Map.of("repriceTo", 9.9, "costFloor", 8.0));
        return new GuardrailPolicy(1L, "v1", List.of(reallocate, reprice));
    }
}
