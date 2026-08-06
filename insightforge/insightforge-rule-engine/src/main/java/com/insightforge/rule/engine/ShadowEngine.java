package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.GuardrailPolicy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 影子模式（上线前安全演习，评估方案 §5）。
 *
 * 大白话：新 DSL 要上线前，先拿它对实时 tick 副本跑一遍决策，和「当前线上 DSL」的决策比一比。
 * 注意：影子模式<b>只比对决策、不执行任何写</b>——所以绝对安全。
 * 一致性 = 两张工单清单相等；不一致就记一笔（GuardrailMetrics.recordShadow(false)），
 * 提示「这个新策略会改变行为，需要人工评审」。
 */
@Component
public class ShadowEngine {

    private final RuleEngineService engine;
    private final GuardrailMetrics metrics;

    public ShadowEngine(RuleEngineService engine, GuardrailMetrics metrics) {
        this.engine = engine;
        this.metrics = metrics;
    }

    public ShadowResult compare(SalesTick tick, GuardrailPolicy candidate, GuardrailPolicy current) {
        List<ControlInstruction> candidateOut = engine.evaluateWithPolicy(candidate, tick);
        List<ControlInstruction> currentOut = engine.evaluateWithPolicy(current, tick);
        boolean matched = instructionsEqual(candidateOut, currentOut);
        metrics.recordShadow(matched);
        return new ShadowResult(matched, candidateOut, currentOut);
    }

    private boolean instructionsEqual(List<ControlInstruction> a, List<ControlInstruction> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ControlInstruction x = a.get(i);
            ControlInstruction y = b.get(i);
            if (!Objects.equals(x.getAction(), y.getAction())
                    || !Objects.equals(x.getParams(), y.getParams())
                    || !Objects.equals(x.getRiskLevel(), y.getRiskLevel())) {
                return false;
            }
        }
        return true;
    }

    public record ShadowResult(boolean matched,
                               List<ControlInstruction> candidate,
                               List<ControlInstruction> current) {
    }
}
