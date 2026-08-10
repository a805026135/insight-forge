package com.insightforge.agent.core.evaluation;

import com.insightforge.agent.core.planning.GoldenEvalHarness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 评估报告（大白话：一次评估闭环跑完，所有结论 + 「及格/不及格」都在这）。
 *
 * 它把四件事聚到一起：
 *   1) golden 验收结果（历史正向 case 是否还全过）
 *   2) 失败样本回归结果（曾经的危险输入是否仍被安全拒绝）
 *   3) A/B 实验（AI 控盘 vs 人工规则，客观 + 定性）
 *   4) 系统指标报告（含正确失败率红线）
 *
 * 最关键的是 gatePassed()：拿上面所有硬指标去撞 EvaluationThresholds 这道及格线。
 * 任意一项不达标的，都写进 gateFailures() 并让 gatePassed()=false——这就是
 * 「CI 阈值失败阻断」的依据（JUnit 里断言它，mvn test 不过就红，CI 就被挡）。
 */
public class EvaluationReport {

    private final List<GoldenEvalHarness.GoldenResult> goldenResults;
    private final List<RegressionResult> regressionResults;
    private final AbExperiment abExperiment;
    private final SystemRunMetrics systemMetrics;
    private final EvaluationThresholds thresholds;

    /** 门槛未通过的原因（人话），供报告/CI 日志打印。 */
    private final List<String> gateFailures = new ArrayList<>();

    public EvaluationReport(List<GoldenEvalHarness.GoldenResult> goldenResults,
                            List<RegressionResult> regressionResults,
                            AbExperiment abExperiment,
                            SystemRunMetrics systemMetrics,
                            EvaluationThresholds thresholds) {
        this.goldenResults = goldenResults;
        this.regressionResults = regressionResults;
        this.abExperiment = abExperiment;
        this.systemMetrics = systemMetrics;
        this.thresholds = thresholds;
    }

    /** golden 验收率（0~1）。 */
    public double goldenAcceptanceRate() {
        if (goldenResults.isEmpty()) {
            return 0d;
        }
        long ok = goldenResults.stream().filter(GoldenEvalHarness.GoldenResult::accepted).count();
        return (double) ok / goldenResults.size();
    }

    /** 失败样本回归通过率（0~1）。 */
    public double regressionPassRate() {
        if (regressionResults.isEmpty()) {
            return 0d;
        }
        long ok = regressionResults.stream().filter(RegressionResult::passed).count();
        return (double) ok / regressionResults.size();
    }

    /**
     * 门槛判定（Fail-Close 核心）。
     * 任一门槛不达标 → 收集原因 → 返回 false。幂等可重复调用。
     */
    public boolean gatePassed() {
        gateFailures.clear();
        if (goldenAcceptanceRate() < thresholds.minGoldenAcceptanceRate().doubleValue()) {
            gateFailures.add("golden 验收率不足（期望≥" + thresholds.minGoldenAcceptanceRate() + "，实际" + goldenAcceptanceRate() + "）");
        }
        if (regressionPassRate() < thresholds.minRegressionPassRate().doubleValue()) {
            gateFailures.add("失败样本回归通过率不足（期望≥" + thresholds.minRegressionPassRate() + "，实际" + regressionPassRate() + "）");
        }
        // Phase 14：真实模拟揭示固定成本下降价换量必伤毛利，故把「售罄率提升」门槛改为
        // 「毛利显著提升 + 售罄率不劣」——利润是硬证据，清货能力是底线。
        if (abExperiment.ai().sellThroughRate().compareTo(abExperiment.manual().sellThroughRate()) < 0) {
            gateFailures.add("AI 售罄率劣于人工（期望≥" + abExperiment.manual().sellThroughRate()
                    + "，实际" + abExperiment.ai().sellThroughRate() + "）");
        }
        if (abExperiment.grossMarginLift().compareTo(thresholds.minGrossMarginLift()) < 0) {
            gateFailures.add("AI 毛利率提升低于阈值（期望≥" + thresholds.minGrossMarginLift()
                    + "，实际" + abExperiment.grossMarginLiftPct() + "）");
        }
        if (abExperiment.ai().grossMarginRate().compareTo(thresholds.minGrossMarginFloor()) < 0) {
            gateFailures.add("AI 毛利率低于地板（期望≥" + thresholds.minGrossMarginFloor() + "，实际" + abExperiment.ai().grossMarginRate() + "）");
        }
        BigDecimal cfr = systemMetrics.correctFailureRate();
        if (cfr.signum() < 0 || cfr.compareTo(thresholds.maxCorrectFailureRate()) > 0) {
            gateFailures.add("正确失败率越界（期望≤" + thresholds.maxCorrectFailureRate() + "，实际" + cfr + "）");
        }
        return gateFailures.isEmpty();
    }

    // —— 访问器（报告/大屏序列化用） ——

    public List<GoldenEvalHarness.GoldenResult> goldenResults() {
        return goldenResults;
    }

    public List<RegressionResult> regressionResults() {
        return regressionResults;
    }

    public AbExperiment abExperiment() {
        return abExperiment;
    }

    public SystemRunMetrics systemMetrics() {
        return systemMetrics;
    }

    public EvaluationThresholds thresholds() {
        return thresholds;
    }

    public List<String> gateFailures() {
        return gateFailures;
    }

    /** 一条失败样本回归结果。 */
    public record RegressionResult(FailureSample.Item sample,
                                   com.insightforge.agent.core.runtime.AgentRunStatus actualStatus,
                                   boolean passed) {
    }
}
