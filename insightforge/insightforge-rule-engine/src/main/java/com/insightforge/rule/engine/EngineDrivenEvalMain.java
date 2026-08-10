package com.insightforge.rule.engine;

import com.insightforge.agent.core.evaluation.AbExperiment;
import com.insightforge.agent.core.evaluation.BusinessMetrics;
import com.insightforge.agent.core.evaluation.BusinessMetricsSource;
import com.insightforge.agent.core.evaluation.EvaluationThresholds;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 规则引擎驱动的真实 A/B 评估 CLI（Phase 14）。
 *
 * 纯 Java 入口（不需要 Spring/DB）：手工装配翻译器 + 编译器 + 引擎，
 * 在模拟秒杀市场上跑「人工规则 vs AI 控盘」，打印客观指标与门槛判定。
 * 门槛不过以非零码退出（CI 可接）。
 *
 * 运行：java -cp ... com.insightforge.rule.engine.EngineDrivenEvalMain
 * 或 mvn -pl insightforge-rule-engine -am test-compile exec:java -Dexec.mainClass=...
 */
public final class EngineDrivenEvalMain {

    private EngineDrivenEvalMain() {
    }

    public static void main(String[] args) {
        RuleEngineService engine = new RuleEngineService(
                null, // 评估不查策略源：evaluateWithPolicy 直接给 DSL
                new DslToDroolsTranslator(),
                new DroolsRuleCompiler());
        // 可选参数 args[0]：真 LLM/Agent 产出的 DSL JSON 文件（GuardrailPolicy 格式），
        // 让 A/B 评估跑「真实策略」而非内置演示策略。
        GuardrailPolicy policy = null;
        if (args.length > 0) {
            policy = loadPolicy(args[0]);
            System.out.println("使用外部 DSL：" + args[0] + "（规则数=" + policy.rules().size() + "）");
        }
        BusinessMetricsSource source = policy == null
                ? new SimulatedBusinessMetricsSource(new SeckillSimulator(), engine)
                : new SimulatedBusinessMetricsSource(new SeckillSimulator(), engine, policy);

        BusinessMetrics manual = source.manualRuleMetrics();
        BusinessMetrics ai = source.aiControlledMetrics();
        AbExperiment ab = new AbExperiment(manual, ai, "模拟市场（确定性需求模型）双轨评估");

        System.out.println("========== 规则引擎驱动 A/B 评估（Phase 14 真实模拟） ==========");
        System.out.printf("  人工规则 : 售罄率 %.1f%%  毛利率 %.1f%%  营收 %s%n",
                manual.sellThroughRate().movePointRight(2), manual.grossMarginRate().movePointRight(2), manual.revenue());
        System.out.printf("  AI 控盘  : 售罄率 %.1f%%  毛利率 %.1f%%  营收 %s%n",
                ai.sellThroughRate().movePointRight(2), ai.grossMarginRate().movePointRight(2), ai.revenue());
        System.out.printf("  售罄率提升 %s，毛利率提升 %s，胜方 %s%n",
                ab.sellThroughLiftPct(), ab.grossMarginLiftPct(), ab.winner());

        EvaluationThresholds thresholds = EvaluationThresholds.defaults();
        boolean sellThroughOk = ai.sellThroughRate().compareTo(manual.sellThroughRate()) >= 0;
        boolean marginOk = ab.grossMarginLift().compareTo(thresholds.minGrossMarginLift()) >= 0;
        boolean floorOk = ai.grossMarginRate().compareTo(thresholds.minGrossMarginFloor()) >= 0;
        System.out.println("  门槛：AI 售罄率不劣于人工=" + sellThroughOk
                + "，毛利提升≥" + thresholds.minGrossMarginLift() + "=" + marginOk
                + "，毛利地板≥" + thresholds.minGrossMarginFloor() + "=" + floorOk);
        if (!(sellThroughOk && marginOk && floorOk)) {
            System.err.println("❌ EVAL GATE FAILED（模拟）");
            System.exit(1);
        }
        System.out.println("✅ EVAL GATE PASSED（模拟，指标由规则引擎真实执行产出）");
    }

    private static GuardrailPolicy loadPolicy(String path) {
        try {
            return new ObjectMapper().readValue(Files.readString(Path.of(path)), GuardrailPolicy.class);
        } catch (Exception e) {
            System.err.println("无法加载 DSL 文件 " + path + "：" + e.getMessage());
            System.exit(2);
            return null; // unreachable
        }
    }
}
