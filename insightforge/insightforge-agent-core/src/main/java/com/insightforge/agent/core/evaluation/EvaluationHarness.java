package com.insightforge.agent.core.evaluation;

import com.insightforge.agent.core.monitor.TraceRecorder;
import com.insightforge.agent.core.planning.DslCompiler;
import com.insightforge.agent.core.planning.GuardrailPolicyAgent;
import com.insightforge.agent.core.planning.GuardrailPolicyRepository;
import com.insightforge.agent.core.planning.GoldenEvalHarness;
import com.insightforge.agent.core.planning.IndependentCritic;
import com.insightforge.agent.core.planning.MultiAgentOrchestrator;
import com.insightforge.agent.core.planning.PlanningResult;
import com.insightforge.agent.core.planning.RoleAgent;
import com.insightforge.agent.core.runtime.AgentMetrics;
import com.insightforge.agent.core.runtime.CheckpointService;
import com.insightforge.agent.core.runtime.InterruptGate;
import com.insightforge.agent.core.runtime.AgentRunStatus;
import com.insightforge.agent.core.runtime.AgentTask;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 评估闭环编排器（大白话：把「证明系统没问题」这件事一次性跑完）。
 *
 * 它串起四段（对应 EvaluationReport）：
 *   ① golden 回归：复用 Phase 8 的 GoldenEvalHarness，把历史正向 case 重跑一遍；
 *   ② 失败样本回归：用「强制不合规」编排器重放危机输入，断言仍被安全拒绝（Fail-Close 不退化的证据）；
 *   ③ A/B 实验：从 BusinessMetricsSource 取 AI/人工两边经营结果，算出提升 + 定性结论；
 *   ④ 系统指标报告：从 SystemRunMetricsSource 取正确失败率等红线读数。
 *
 * 设计为 @Component：Spring 自动把各规划组件与数据源注入；同时门槛逻辑对 JUnit / CI 都一致。
 * 注意失败样本重放的「rejectOrchestrator」是这里用 forceInvalidPolicy=true 现拧的一个编排器，
 * 与线上正常编排器（forceInvalidPolicy=false）共享同一套编译/校验/发布组件——证明的是
 * 「同一套护栏在危险输入下依然 Fail-Close」，而非另起炉灶。
 */
@Component
public class EvaluationHarness {

    private static final String MODEL_VERSION = "local-det-1.0";
    private static final String PROMPT_VERSION = "insightforge-prompt-1.0";

    private final GoldenEvalHarness goldenHarness;
    private final MultiAgentOrchestrator rejectOrchestrator;
    private final BusinessMetricsSource businessMetricsSource;
    private final SystemRunMetricsSource systemRunMetricsSource;
    private final QualitativeAssessor qualitativeAssessor;
    private final EvaluationThresholds thresholds;

    public EvaluationHarness(MultiAgentOrchestrator orchestrator,
                             RoleAgent roleAgent,
                             GuardrailPolicyAgent policyAgent,
                             DslCompiler dslCompiler,
                             IndependentCritic critic,
                             GuardrailPolicyRepository repository,
                              TraceRecorder traceRecorder,
                              AgentMetrics metrics,
                              CheckpointService checkpointService,
                              InterruptGate interruptGate,
                              BusinessMetricsSource businessMetricsSource,
                             SystemRunMetricsSource systemRunMetricsSource,
                             QualitativeAssessor qualitativeAssessor,
                             EvaluationThresholds thresholds) {
        // ① golden 回归复用既有 harness（走正常编排器，期望全 SUCCESS）
        this.goldenHarness = new GoldenEvalHarness(orchestrator);
        // ② 失败样本回归用的「强制不合规」编排器（期望全 CORRECT_FAILURE）
        this.rejectOrchestrator = new MultiAgentOrchestrator(
                roleAgent, policyAgent, dslCompiler, critic, repository,
                traceRecorder, metrics, checkpointService, interruptGate,
                3, true, MODEL_VERSION, PROMPT_VERSION);
        this.businessMetricsSource = businessMetricsSource;
        this.systemRunMetricsSource = systemRunMetricsSource;
        this.qualitativeAssessor = qualitativeAssessor;
        this.thresholds = thresholds;
    }

    /** 跑完整评估闭环，产出报告（含门槛判定）。 */
    public EvaluationReport run() throws Exception {
        List<GoldenEvalHarness.GoldenResult> golden = goldenHarness.runAll();
        List<EvaluationReport.RegressionResult> regression = replayFailureSamples();

        BusinessMetrics manual = businessMetricsSource.manualRuleMetrics();
        BusinessMetrics ai = businessMetricsSource.aiControlledMetrics();
        String qualitative = qualitativeAssessor.assess(new AbExperiment(manual, ai, ""));
        AbExperiment ab = new AbExperiment(manual, ai, qualitative);

        SystemRunMetrics sys = systemRunMetricsSource.metrics();
        return new EvaluationReport(golden, regression, ab, sys, thresholds);
    }

    /** 失败样本回归重放：每个样本走「强制不合规」编排器，断言终态 == 期望的安全态。 */
    public List<EvaluationReport.RegressionResult> replayFailureSamples() throws Exception {
        List<FailureSample.Item> samples = FailureSample.load();
        List<EvaluationReport.RegressionResult> out = new ArrayList<>();
        for (FailureSample.Item s : samples) {
            AgentTask task = new AgentTask(s.activityId(), s.goal(), null);
            PlanningResult r = rejectOrchestrator.plan(task);
            AgentRunStatus expect = AgentRunStatus.valueOf(s.expect());
            boolean passed = r.status() == expect;
            out.add(new EvaluationReport.RegressionResult(s, r.status(), passed));
        }
        return out;
    }
}
