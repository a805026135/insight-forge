package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回测骨架（历史重放，评估方案 §5：oversell=0 / SLA 达标）。
 *
 * 大白话：把历史上真实的销售心跳序列，重新喂给候选 DSL 跑一遍，检查两件事：
 * ① 没有产生「违规指令」（REALLOCATE 数量为负 / REPRICE 低于成本）——这就是「超卖风险」的代理指标；
 * ② 每条决策耗时都在 SLA 内（默认 50ms，证明 AI 控盘不拖慢系统）。
 *
 * 因为 DSL 已通过 Phase 8 护栏 + Phase 9 DRL 编译双重 Fail-Close，违规指令本应为 0——
 * 回测用历史数据把这个「0」坐实，作为上线依据（L3 结果评估硬门槛）。
 */
@Component
public class BacktestHarness {

    private final RuleEngineService engine;
    private final long slaThresholdMs;

    @Autowired
    public BacktestHarness(RuleEngineService engine) {
        this(engine, 50L);
    }

    public BacktestHarness(RuleEngineService engine, long slaThresholdMs) {
        this.engine = engine;
        this.slaThresholdMs = slaThresholdMs;
    }

    public BacktestReport run(GuardrailPolicy policy, List<SalesTick> history) {
        // 预热：生产里候选 DSL 已经过 Phase 9 的 Fail-Close 门禁（DRL 编译校验）才会上线，
        // 因此编译成本是一次性的、不计入「每条决策的实时耗时」。这里先跑一次把 KieContainer 编译进缓存，
        // 后续重放的延迟才反映真实的稳态决策耗时（毫秒级）。
        if (!history.isEmpty()) {
            engine.evaluateWithPolicy(policy, history.get(0));
        }
        int violationCount = 0;
        long maxLatencyMs = 0;
        for (SalesTick tick : history) {
            long start = System.nanoTime();
            List<ControlInstruction> out = engine.evaluateWithPolicy(policy, tick);
            long latency = (System.nanoTime() - start) / 1_000_000;
            maxLatencyMs = Math.max(maxLatencyMs, latency);
            for (ControlInstruction ci : out) {
                if (isViolation(ci)) {
                    violationCount++;
                }
            }
        }
        boolean oversellFree = violationCount == 0;
        boolean slaMet = maxLatencyMs <= slaThresholdMs;
        return new BacktestReport(oversellFree, slaMet, violationCount, maxLatencyMs, history.size());
    }

    /** 违规 = 会制造超卖/透支的指令（这些本应被 Fail-Close 拦住，回测作为最后兜底校验）。 */
    private boolean isViolation(ControlInstruction ci) {
        if (ci.getAction() == Action.REALLOCATE) {
            return ci.intParam("amount", 0) < 0;
        }
        if (ci.getAction() == Action.REPRICE) {
            double repriceTo = ci.doubleParam("repriceTo", Double.MAX_VALUE);
            double costFloor = ci.doubleParam("costFloor", Double.MIN_VALUE);
            return repriceTo < costFloor;
        }
        return false;
    }

    public record BacktestReport(boolean oversellFree, boolean slaMet, int violationCount,
                                 long maxLatencyMs, int tickCount) {
        public boolean passed() {
            return oversellFree && slaMet;
        }
    }
}
