package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.GuardrailPolicy;
import org.kie.api.runtime.KieContainer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 护栏规则引擎执行服务（数据面，确定性，零 LLM）。
 *
 * 大白话：这是「执行官」本体。给它一个活动 ID + 一笔销售心跳(tick)，它就去拿最新的 DSL、
 * 翻译成 Drools 规则、把 tick 当事实丢进去跑，最后吐出一张张「控盘工单」(ControlInstruction)。
 * 它只做「只读匹配 + 产出指令」，**绝不碰库存/订单**——真正的写操作交给 ControlInstructionExecutor。
 */
@Service
public class RuleEngineService {

    private final PolicySource policySource;
    private final DslToDroolsTranslator translator;
    private final DroolsRuleCompiler compiler;

    public RuleEngineService(PolicySource policySource,
                             DslToDroolsTranslator translator,
                             DroolsRuleCompiler compiler) {
        this.policySource = policySource;
        this.translator = translator;
        this.compiler = compiler;
    }

    /** 给定活动 + tick，产出该 tick 触发的全部控盘指令（只读匹配，不写）。 */
    public List<ControlInstruction> evaluate(long activityId, SalesTick tick) {
        GuardrailPolicy policy = policySource.loadLatest(activityId);
        if (policy == null) {
            return List.of();
        }
        return evaluateWithPolicy(policy, tick);
    }

    /** 用「指定的 DSL」跑（影子模式 / 回测用：不查库，直接用给定候选 DSL）。 */
    public List<ControlInstruction> evaluateWithPolicy(GuardrailPolicy policy, SalesTick tick) {
        String key = policy.activityId() + ":" + policy.version();
        String drl = translator.translate(policy);
        KieContainer container = compiler.compileOrCached(key, drl);
        return runSession(container, tick);
    }

    private List<ControlInstruction> runSession(KieContainer container, SalesTick tick) {
        var session = container.newKieSession();
        List<ControlInstruction> out = new ArrayList<>();
        session.setGlobal("instructions", out);
        try {
            session.insert(tick);
            session.fireAllRules();
        } finally {
            session.dispose();
        }
        return out;
    }
}
