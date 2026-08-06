package com.insightforge.agent.core.evaluation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估门槛逻辑单元测试（纯 JUnit，不依赖 Spring / DB）。
 *
 * 验证 EvaluationReport.gatePassed() 这道 Fail-Close 闸门真的会「拦人」：
 * 当 golden 验收率 / 售罄率提升等硬指标不达标时，返回 false 并给出人话原因。
 */
class EvaluationReportTest {

    private static final BusinessMetrics MANUAL =
            new BusinessMetrics(100, 80, new BigDecimal("8000.00"), new BigDecimal("5760.00"));
    private static final BusinessMetrics AI =
            new BusinessMetrics(100, 95, new BigDecimal("9500.00"), new BigDecimal("6080.00"));
    private static final AbExperiment AB = new AbExperiment(MANUAL, AI, "定性结论占位");
    private static final SystemRunMetrics SYS =
            new SystemRunMetrics(100, 5, 1, 100, new BigDecimal("0.10"));

    @Test
    void gateFailsWhenThresholdsBroken() {
        // 故意把「售罄率提升」门槛设成不可能的 1000%，看闸门是否拦下
        EvaluationThresholds broken = new EvaluationThresholds(
                BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("10.0"), new BigDecimal("0.20"), BigDecimal.ONE);
        EvaluationReport report = new EvaluationReport(List.of(), List.of(), AB, SYS, broken);

        assertFalse(report.gatePassed(), "门槛被打破时应判定不通过");
        assertTrue(report.gateFailures().stream().anyMatch(s -> s.contains("golden")),
                "空 golden 集应触发验收率不足，实际失败原因=" + report.gateFailures());
        assertTrue(report.gateFailures().stream().anyMatch(s -> s.contains("售罄率")),
                "不可能的售罄率门槛应触发对应失败原因，实际失败原因=" + report.gateFailures());
    }

    @Test
    void gateFailsOnEmptyGoldenWithDefaults() {
        // 默认门槛下，golden 集为空 → 验收率 0 < 1 → 不通过
        EvaluationReport report = new EvaluationReport(
                List.of(), List.of(), AB, SYS, EvaluationThresholds.defaults());
        assertFalse(report.gatePassed());
    }
}
