package com.insightforge.agent.core.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A/B 实验结果（大白话：把「AI 控盘」和「人工规则」放同一个赛场比一比）。
 *
 * - manual：对照组，商家用静态人工规则（固定价、固定库存分配）。
 * - ai：实验组，Agent 离线规划 + 规则引擎在线动态调价/调拨。
 * - qualitative：定性补充结论（可由 LLM 生成，本地用确定性桩）。
 *
 * 两个「提升」指标是评估闭环的核心证据：
 *   - sellThroughLift：AI 售罄率相对人工的提升比例
 *   - grossMarginLift：AI 毛利率相对人工的提升比例
 * winner()：售罄率与毛利率都不低于人工时判 AI 胜（双指标都更好才敢说「AI 真有用」）。
 */
public record AbExperiment(
        BusinessMetrics manual,
        BusinessMetrics ai,
        String qualitative
) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** AI 售罄率相对人工的提升（0.158 = +15.8%）。基线为 0 时返回 0。 */
    public BigDecimal sellThroughLift() {
        BigDecimal base = manual.sellThroughRate();
        if (base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return ai.sellThroughRate().subtract(base)
                .divide(base, 4, RoundingMode.HALF_UP);
    }

    /** AI 毛利率相对人工的提升。 */
    public BigDecimal grossMarginLift() {
        BigDecimal base = manual.grossMarginRate();
        if (base.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return ai.grossMarginRate().subtract(base)
                .divide(base, 4, RoundingMode.HALF_UP);
    }

    /** 综合胜负判定：售罄率与毛利率都 ≥ 人工时，AI 胜。 */
    public String winner() {
        boolean aiWins = ai.sellThroughRate().compareTo(manual.sellThroughRate()) >= 0
                && ai.grossMarginRate().compareTo(manual.grossMarginRate()) >= 0;
        return aiWins ? "AI_CONTROL" : "MANUAL";
    }

    /** 把提升比例格式化成百分数字符串，给报告/大屏用。 */
    public String sellThroughLiftPct() {
        return sellThroughLift().multiply(HUNDRED).toString() + "%";
    }

    public String grossMarginLiftPct() {
        return grossMarginLift().multiply(HUNDRED).toString() + "%";
    }
}
