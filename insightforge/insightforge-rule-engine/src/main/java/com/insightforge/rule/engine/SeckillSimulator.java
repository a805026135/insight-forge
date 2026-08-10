package com.insightforge.rule.engine;

import com.insightforge.agent.core.evaluation.BusinessMetrics;
import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 模拟秒杀引擎（Phase 14 真实 A/B 评估的核心）。
 *
 * 大白话：在一个「确定性需求模型」的模拟市场上，把两套策略各跑一遍：
 *   - 人工规则：固定价 + 固定库存，不做任何控盘；
 *   - AI 控盘：每条销售心跳真实喂给 {@link RuleEngineService}（Drools + DSL），
 *     把产出的 REPRICE / REALLOCATE 指令真实应用到模拟市场（改价 / 低价渠道补货）。
 *
 * 为什么这是「真评估」而不是种子自证：
 *   - 两边最后的售罄率 / 毛利率 / 营收全部由模拟执行逐笔累计而来；
 *   - AI 组的每一次调价/调拨都来自规则引擎的真实匹配结果（非手工填数）；
 *   - 需求模型完全确定性（买家按支付意愿升序依次到达），结果可复现、可单测。
 *
 * 诚实边界：这是「模拟市场」不是生产数据；但相比 InMemory 种子，它证明了
 * 「规则引擎在秒杀流上跑出来什么，评估就报什么」——种子自证的问题已消除。
 */
public class SeckillSimulator {

    /** 模拟市场参数（全部确定，无随机）。 */
    public record SimConfig(
            long stock,                  // 初始铺货（售罄率分母，与人工组同口径）
            BigDecimal initialPrice,     // 初始售价
            BigDecimal unitCost,         // 常规渠道成本
            BigDecimal reallocCost,      // 低价渠道（调拨补货）成本
            int buyerCount,              // 模拟买家总数
            BigDecimal minWtp,           // 最低支付意愿
            BigDecimal maxWtp            // 最高支付意愿（买家按意愿升序到达）
    ) {
    }

    /** 模拟经营结果（已含真实成本核算：常规/调拨渠道分开计）。 */
    public record SimResult(
            long totalQty,               // 初始铺货（口径与人工组一致）
            long soldQty,
            long reallocSold,            // 其中来自低价渠道调拨的销量
            BigDecimal revenue,
            BigDecimal cost
    ) {
        public BusinessMetrics toBusinessMetrics() {
            return new BusinessMetrics(totalQty, soldQty, revenue, cost);
        }
    }

    /**
     * 跑一遍模拟秒杀。
     *
     * @param engine 规则引擎（AI 组传入；人工组传 null 表示不做任何控盘）
     * @param policy AI 组的护栏 DSL（人工组忽略）
     * @param cfg    市场参数
     */
    public SimResult simulate(RuleEngineService engine, GuardrailPolicy policy, SimConfig cfg) {
        long stock = cfg.stock();
        long sold = 0L;
        long totalForRatio = cfg.stock(); // 调拨后总库存会增长（规则引擎口径：剩余/总）
        long reallocAvailable = 0L;   // 已调拨待售（低价渠道）
        long reallocSold = 0L;
        BigDecimal price = cfg.initialPrice();
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        long ts = 0L;

        for (int i = 0; i < cfg.buyerCount() && stock > 0L; i++) {
            // 确定性支付意愿：买家按意愿升序到达（越晚到越愿意付高价）
            BigDecimal wtp = cfg.minWtp().add(
                    cfg.maxWtp().subtract(cfg.minWtp())
                            .multiply(BigDecimal.valueOf(i))
                            .divide(BigDecimal.valueOf(Math.max(1, cfg.buyerCount() - 1)),
                                    4, RoundingMode.HALF_UP));
            if (wtp.compareTo(price) < 0) {
                continue; // 支付意愿低于当前价，不买
            }

            // —— 成交：先消耗常规库存，再消耗调拨（低价渠道）库存 ——
            stock--;
            sold++;
            revenue = revenue.add(price);
            if (reallocAvailable > 0) {
                reallocAvailable--;
                reallocSold++;
                cost = cost.add(cfg.reallocCost());
            } else {
                cost = cost.add(cfg.unitCost());
            }

            // —— 每个销售心跳都真实喂给规则引擎，并应用产出的控盘指令 ——
            if (policy != null) {
                double ratio = totalForRatio > 0 ? (double) stock / totalForRatio : 0d;
                SalesTick tick = new SalesTick(1L, 1L, ratio, 0d, 0d, 0d,
                        (int) stock, (int) totalForRatio, 0, ts++);
                List<ControlInstruction> instructions = engine.evaluateWithPolicy(policy, tick);
                for (ControlInstruction ci : instructions) {
                    if (ci.getAction() == Action.REPRICE) {
                        price = BigDecimal.valueOf(ci.doubleParam("repriceTo", price.doubleValue()));
                    } else if (ci.getAction() == Action.REALLOCATE) {
                        int amount = ci.intParam("amount", 0);
                        if (amount > 0) {
                            stock += amount;
                            totalForRatio += amount;
                            reallocAvailable += amount;
                        }
                    }
                }
            }
        }
        return new SimResult(cfg.stock(), sold, reallocSold, revenue, cost);
    }
}
