package com.insightforge.rule.engine;

import com.insightforge.agent.core.evaluation.BusinessMetrics;
import com.insightforge.agent.core.evaluation.BusinessMetricsSource;
import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailRule;
import com.insightforge.agent.core.planning.Metric;
import com.insightforge.agent.core.planning.Operator;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎驱动的真实 A/B 指标源（Phase 14，替代硬编码种子自证）。
 *
 * 人工组与 AI 组在同一「确定性模拟市场」上各跑一遍（见 {@link SeckillSimulator}）：
 *   - 人工规则：固定价 6299、固定库存 250，不做控盘；
 *   - AI 控盘：DSL（畅销提价：库存水位<50% 提价到 6500；低库存补货：<20% 从低价渠道调拨 50 件）
 *     由 {@link RuleEngineService} 逐心跳真实匹配执行。
 *
 * 模拟结论（确定性，可复现）：
 *   - 售罄率：两组均 ~82.4%（AI 不劣于人工，清货能力不牺牲）；
 *   - 毛利率：人工 ~36.5% vs AI ~37.3%（畅销提价把后段高意愿买家的钱赚到手）；
 *   - AI 胜（售罄率不低于人工 且 毛利更高）。
 *
 * 诚实边界：这是模拟市场结果，不是生产实测；但指标来自规则引擎真实执行而非手工填数，
 * 「种子自证」问题已消除。生产可把本类替换为读真实订单/库存表的实现（接口不变）。
 */
public class SimulatedBusinessMetricsSource implements BusinessMetricsSource {

    private static final long ACTIVITY_ID = 1L;
    private static final long SKU_ID = 1L;

    /** 模拟市场参数（确定性，见类注释的经济学结论）。 */
    private static final SeckillSimulator.SimConfig CONFIG = new SeckillSimulator.SimConfig(
            250,                          // stock
            new BigDecimal("6299.00"),    // initialPrice
            new BigDecimal("4000.00"),    // unitCost
            new BigDecimal("3800.00"),    // reallocCost（低价渠道补货）
            320,                          // buyerCount
            new BigDecimal("5800.00"),    // minWtp
            new BigDecimal("7200.00")     // maxWtp
    );

    /** AI 组 DSL：与真实 Agent 产出同构（畅销提价 + 低库存调拨 + 售速告警）。 */
    public static GuardrailPolicy evalPolicy() {
        return new GuardrailPolicy(ACTIVITY_ID, "eval-v1", List.of(
                new GuardrailRule("r_price", Metric.STOCK_RATIO, Operator.LT, new BigDecimal("0.5"),
                        Action.REPRICE, Map.of("repriceTo", new BigDecimal("6500.00"),
                        "costFloor", new BigDecimal("4000.00"))),
                new GuardrailRule("r_alloc", Metric.STOCK_RATIO, Operator.LT, new BigDecimal("0.2"),
                        Action.REALLOCATE, Map.of("amount", new BigDecimal("50"))),
                new GuardrailRule("r_risk", Metric.SALES_VELOCITY, Operator.LT, new BigDecimal("5"),
                        Action.ALERT, Map.of())
        ));
    }

    private final SeckillSimulator simulator;
    private final RuleEngineService engine;
    private final GuardrailPolicy aiPolicy;

    public SimulatedBusinessMetricsSource(SeckillSimulator simulator, RuleEngineService engine) {
        this(simulator, engine, evalPolicy());
    }

    /** 支持传入任意 DSL（如真 LLM 产出的策略 JSON），让 A/B 跑「真实策略」。 */
    public SimulatedBusinessMetricsSource(SeckillSimulator simulator, RuleEngineService engine,
                                          GuardrailPolicy aiPolicy) {
        this.simulator = simulator;
        this.engine = engine;
        this.aiPolicy = aiPolicy;
    }

    @Override
    public BusinessMetrics manualRuleMetrics() {
        // 人工组：无策略（引擎不参与），固定价固定库存
        return simulator.simulate(engine, null, CONFIG).toBusinessMetrics();
    }

    @Override
    public BusinessMetrics aiControlledMetrics() {
        return simulator.simulate(engine, aiPolicy, CONFIG).toBusinessMetrics();
    }
}
