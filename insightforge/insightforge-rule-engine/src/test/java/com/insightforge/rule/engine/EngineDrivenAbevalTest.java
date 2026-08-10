package com.insightforge.rule.engine;

import com.insightforge.agent.core.evaluation.AbExperiment;
import com.insightforge.agent.core.evaluation.BusinessMetrics;
import com.insightforge.agent.core.evaluation.EvaluationThresholds;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 14 真实 A/B 评估回归测试（规则引擎驱动，非种子自证）。
 *
 * 在确定性模拟秒杀市场上跑两套策略：
 *   - 人工规则：固定价 6299 / 固定库存 250；
 *   - AI 控盘：DSL 由 Drools 逐心跳真实匹配（畅销提价 + 低库存调拨 + 售速告警）。
 * 断言 AI 在「毛利显著提升 + 售罄率不劣」下胜出——指标全部来自模拟执行逐笔累计。
 */
class EngineDrivenAbevalTest {

    private RuleEngineService buildEngine() {
        return new RuleEngineService(
                null, // 评估直接给 DSL（evaluateWithPolicy），不需要策略源
                new DslToDroolsTranslator(),
                new DroolsRuleCompiler());
    }

    @Test
    void aiControlWinsWithRealEngineExecution() {
        RuleEngineService engine = buildEngine();
        SimulatedBusinessMetricsSource source =
                new SimulatedBusinessMetricsSource(new SeckillSimulator(), engine);

        BusinessMetrics manual = source.manualRuleMetrics();
        BusinessMetrics ai = source.aiControlledMetrics();
        AbExperiment ab = new AbExperiment(manual, ai, "模拟市场双轨评估");

        // ① 指标确实由模拟产出（非手工填数）：两组数字符合确定性市场的预期量级
        assertTrue(manual.sellThroughRate().compareTo(new BigDecimal("0.80")) > 0,
                "人工组售罄率应约 82.4%，实际=" + manual.sellThroughRate());
        assertTrue(ai.revenue().compareTo(manual.revenue()) > 0,
                "畅销提价应带来更高营收，AI=" + ai.revenue() + " manual=" + manual.revenue());

        // ② 经济结论：AI 毛利显著提升（≥ 门槛 +1%），且售罄率不劣于人工
        BigDecimal marginLift = ab.grossMarginLift();
        assertTrue(marginLift.compareTo(EvaluationThresholds.defaults().minGrossMarginLift()) >= 0,
                "AI 毛利率提升应 ≥ 门槛，实际=" + marginLift);
        assertTrue(ai.sellThroughRate().compareTo(manual.sellThroughRate()) >= 0,
                "AI 售罄率不得劣于人工，AI=" + ai.sellThroughRate() + " manual=" + manual.sellThroughRate());

        // ③ 胜方判定与门槛
        assertEquals("AI_CONTROL", ab.winner(), "AI 应双指标不劣胜出");
        assertTrue(ai.grossMarginRate().compareTo(EvaluationThresholds.defaults().minGrossMarginFloor()) >= 0,
                "AI 毛利率应 ≥ 地板 20%，实际=" + ai.grossMarginRate());

        // ④ 可复现性：同一配置跑两次结果一致（确定性）
        BusinessMetrics aiAgain = source.aiControlledMetrics();
        assertEquals(ai, aiAgain, "模拟必须确定性可复现");
    }
}
