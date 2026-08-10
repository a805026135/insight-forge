package com.insightforge.agent.core.evaluation;

import java.math.BigDecimal;

/**
 * 评估门槛（大白话：CI / 发布前那道「及格线」）。
 *
 * 每条门槛都是一道 Fail-Close 闸门——不达标就阻断（对应 tasks.md「CI 阈值失败阻断」）。
 * 默认值经过设计文档的红线推演：
 *   - golden 验收率必须 100%：历史能过的正向 case 永远不能退化。
 *   - 失败样本回归通过率必须 100%：曾经「正确拒绝」的安全行为永不退化成「假装成功」。
 *   - AI 毛利率相对人工至少 +1%：Phase 14 真实模拟揭示，固定成本下降价换量必然伤毛利，
 *     所以「AI 真有用」的硬证据是利润显著提升 + 售罄率不劣（见 EvaluationReport.gatePassed）。
 *   - AI 毛利率地板 20%：盈利性底线。
 *   - 正确失败率上限 100%：仅作展示红线，不硬性阻断（越高越安全）。
 */
public record EvaluationThresholds(
        BigDecimal minGoldenAcceptanceRate,
        BigDecimal minRegressionPassRate,
        BigDecimal minGrossMarginLift,
        BigDecimal minGrossMarginFloor,
        BigDecimal maxCorrectFailureRate
) {

    public static EvaluationThresholds defaults() {
        return new EvaluationThresholds(
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.01"),
                new BigDecimal("0.20"),
                BigDecimal.ONE
        );
    }
}
