package com.insightforge.api.explain;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 大屏策略可解释性 —— 后端**模板**生成自然语言说明（Phase 10 加分项 #3）。
 *
 * 设计铁律（与「LLM 不进热路径」同源）：解释文案必须是**确定性模板**，
 * 不是 LLM 实时生成。原因有三：
 *   1. 实时 LLM 有延迟/不稳定，大屏要「秒出」；
 *   2. 实时 LLM 可能瞎编（幻觉），而解释文案面向运营决策，错了比没有更糟；
 *   3. 模板零成本、可单测、可审计——每条解释都能追到「是哪个指标越过哪个阈值触发了哪个动作」。
 *
 * 它的输入 {@link ExplainInput} 只是几个字段，不依赖任何外部模块，
 * 所以这一层可以纯 JUnit 单测（见 ExplainabilityServiceTest），不碰 Spring、不碰 DB。
 */
@Service
public class ExplainabilityService {

    /**
     * 生成一条人话解释。
     *
     * @param in 决策事实（活动/SKU/指标/动作/数量/风险）
     * @return 面向运营的中文自然语言说明；空输入返回友好提示而非抛异常（大屏不崩）
     */
    public String explain(ExplainInput in) {
        if (in == null || in.action() == null) {
            return "暂无控盘动作，无需解释。";
        }
        String sku = in.skuId() == null ? "未知SKU" : "SKU-" + in.skuId();
        String metric = in.metric() == null ? "关键指标" : in.metric();
        String value = in.metricValue() == null ? "—" : in.metricValue();
        String threshold = in.threshold() == null ? "—" : in.threshold();
        String risk = in.riskLevel() == null ? "未评级" : in.riskLevel();

        return switch (in.action().toUpperCase()) {
            case "REALLOCATE" -> String.format(
                    "因 %s 的%s达到 %s（越过阈值 %s），规则引擎自动执行「调拨」：从备用库存池调入 %d 件，保障该 SKU 不断货。风险等级：%s。",
                    sku, metric, value, threshold, orZero(in.amount()), risk);
            case "REPRICE" -> String.format(
                    "因 %s 的%s达到 %s（越过阈值 %s），规则引擎自动「调价」至 ¥%s，以平衡供需与毛利。风险等级：%s。",
                    sku, metric, value, threshold, orPrice(in.price()), risk);
            case "REFUSE" -> String.format(
                    "规则引擎检测到一条疑似越权/不安全指令（%s=%s），按 Fail-Close 铁律**拒绝执行**并升级人工复核。风险等级：%s。",
                    metric, value, risk);
            case "ASK" -> String.format(
                    "规则引擎对该决策不确定（%s=%s），按「正确失败」原则主动询问运营，而非擅自执行。风险等级：%s。",
                    metric, value, risk);
            case "ESCALATE" -> String.format(
                    "规则引擎将该决策升级人工处理（%s=%s），等待运营拍板。风险等级：%s。",
                    metric, value, risk);
            case "ALLOW" -> String.format(
                    "%s 的%s处于 %s，未触发任何护栏，维持原策略。风险等级：%s。",
                    sku, metric, value, risk);
            default -> String.format(
                    "%s 触发了动作 %s（%s=%s）。风险等级：%s。",
                    sku, in.action(), metric, value, risk);
        };
    }

    private int orZero(Integer amount) {
        return amount == null ? 0 : amount;
    }

    private String orPrice(BigDecimal price) {
        return price == null ? "—" : price.toPlainString();
    }
}
