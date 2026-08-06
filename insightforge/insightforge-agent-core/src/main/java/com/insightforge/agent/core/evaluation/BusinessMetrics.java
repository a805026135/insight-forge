package com.insightforge.agent.core.evaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一笔经营的「客观硬指标」（大白话：用数字说话，不靠感觉）。
 *
 * 这是双轨评估里「客观优先」那一轨的载体。它只装三类经营结果：
 *   - 卖了多少（soldQty）/ 总共铺了多少货（totalQty）
 *   - 赚了多少钱（revenue = GMV）
 *   - 花了多少成本（cost = 商品成本 COGS）
 * 三个衍生指标全部从这里确定性推导：
 *   - 毛利率 grossMarginRate = (营收 - 成本) / 营收
 *   - 售罄率 sellThroughRate  = 已售 / 总铺货
 *   - 库存周转 inventoryTurnover = 已售 / 平均库存（教学代理，生产用 销售成本/平均库存）
 *
 * 为什么用 BigDecimal 而不是 double？钱和比率精度不能被二进制浮点吃掉，
 * 评估结论要靠它「定量证明」，差 0.001 都可能让门槛判断翻车。
 */
public record BusinessMetrics(
        long totalQty,
        long soldQty,
        BigDecimal revenue,
        BigDecimal cost
) {

    /** 毛利率（0~1）。营收为 0 时定义成 0，避免除零。 */
    public BigDecimal grossMarginRate() {
        if (revenue == null || revenue.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return revenue.subtract(cost).divide(revenue, 4, RoundingMode.HALF_UP);
    }

    /** 售罄率（0~1）。 */
    public BigDecimal sellThroughRate() {
        if (totalQty == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(soldQty)
                .divide(BigDecimal.valueOf(totalQty), 4, RoundingMode.HALF_UP);
    }

    /**
     * 库存周转率（教学代理口径）。
     * 平均库存近似 = (初始 + 剩余) / 2 = 初始 - 已售/2；周转 = 已售 / 平均库存。
     * 售罄越彻底，周转越高（全部卖光 ≈ 2.0）。生产环境应换成「销售成本 / 平均库存」。
     */
    public BigDecimal inventoryTurnover() {
        long remaining = Math.max(0, totalQty - soldQty);
        double avg = (totalQty + remaining) / 2.0;
        if (avg == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(soldQty / avg).setScale(4, RoundingMode.HALF_UP);
    }
}
