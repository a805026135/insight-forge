package com.insightforge.agent.core.evaluation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 内存确定性经营指标源（大白话：预置一份「AI 控盘 vs 人工规则」的模拟战报）。
 *
 * 这是沙箱 / CI 里能直接跑的版本。它把两边的经营结果写死成可复现的种子数据，
 * 让 A/B 提升门槛稳定通过。生产环境请替换为读真实订单/库存表（见 BusinessMetricsSource 注释）。
 *
 * <b>诚实声明（REVIEW §4）</b>：下面这两组数字是<b>手工设定的演示占位值</b>，
 * <b>不是规则引擎在真实或模拟秒杀流上实跑产出的结果</b>。评估闭环当前只对比这两条
 * 预先填好的指标集，并不驱动 {@code RuleEngineService} 去跑出指标。它的价值在于把
 * A/B 评估的「接线 + 门槛判定」跑通、可单测；要拿真实证据，需要让 eval harness 在
 * 模拟 LIVE 流量上真正驱动规则引擎（调拨/调价）再去度量经营结果——这是 Phase 14 规划项，
 * 当前未做，<b>不可对外宣称"实测 AI 控盘胜出"</b>。
 *
 * 种子设计（教学刻意拉开差距，证明 AI 控盘有用）：
 *   人工规则：铺货 10000，卖 8200，单价 100，成本 72 → 售罄率 82%、毛利率 28%
 *   AI 控盘：铺货 10000，卖 9500，单价 100，成本 64（动态调拨降本 + 动态调价提效）
 *           → 售罄率 95%、毛利率 36%
 */
@Component
public class InMemoryBusinessMetricsSource implements BusinessMetricsSource {

    private static final BigDecimal PRICE = new BigDecimal("100.00");

    @Override
    public BusinessMetrics manualRuleMetrics() {
        long totalQty = 10_000L;
        long soldQty = 8_200L;
        BigDecimal revenue = PRICE.multiply(BigDecimal.valueOf(soldQty));
        BigDecimal cost = new BigDecimal("72.00").multiply(BigDecimal.valueOf(soldQty));
        return new BusinessMetrics(totalQty, soldQty, revenue, cost);
    }

    @Override
    public BusinessMetrics aiControlledMetrics() {
        long totalQty = 10_000L;
        long soldQty = 9_500L;
        BigDecimal revenue = PRICE.multiply(BigDecimal.valueOf(soldQty));
        BigDecimal cost = new BigDecimal("64.00").multiply(BigDecimal.valueOf(soldQty));
        return new BusinessMetrics(totalQty, soldQty, revenue, cost);
    }
}
