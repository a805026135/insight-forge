package com.insightforge.agent.core.evaluation;

import java.math.BigDecimal;

/**
 * 评估门槛（大白话：CI / 发布前那道「及格线」）。
 *
 * 每条门槛都是一道 Fail-Close 闸门——不达标就阻断（对应 tasks.md「CI 阈值失败阻断」）。
 * 默认值经过设计文档的红线推演：
 *   - golden 验收率必须 100%：历史能过的正向 case 永远不能退化。
 *   - 失败样本回归通过率必须 100%：曾经「正确拒绝」的安全行为永不退化成「假装成功」。
 *   - AI 售罄率相对人工至少 +5%：证明 AI 控盘确实清货更高效。
 *   - AI 毛利率地板 20%：盈利性底线（本沙箱种子数据 AI 毛利率 36%）。
 *   - 正确失败率上限 100%：仅作展示红线，不硬性阻断（越高越安全）。
 */
public record EvaluationThresholds(
        BigDecimal minGoldenAcceptanceRate,
        BigDecimal minRegressionPassRate,
        BigDecimal minSellThroughLift,
        BigDecimal minGrossMarginFloor,
        BigDecimal maxCorrectFailureRate
) {

    public static EvaluationThresholds defaults() {
        return new EvaluationThresholds(
                BigDecimal.ONE,
                BigDecimal.ONE,
                new BigDecimal("0.05"),
                new BigDecimal("0.20"),
                BigDecimal.ONE
        );
    }
}
