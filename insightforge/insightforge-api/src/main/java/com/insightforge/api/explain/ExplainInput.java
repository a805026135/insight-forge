package com.insightforge.api.explain;

import java.math.BigDecimal;

/**
 * 可解释性输入（Phase 10）。
 *
 * 规则引擎每吐出一条控盘指令（ControlInstruction），大屏就要用**人话**解释「为什么这么做」。
 * 这里 deliberately 用纯数据字段，而不是把整条指令对象搬过来——
 * 因为 rule-engine 模块此刻并未接入 api 的运行时（控制面/数据面分离：Rule-Engine 是独立 Worker），
 * api 这一侧只拿到「已经发生的决策事实」（活动、SKU、指标、动作、数量……），据此生成解释。
 *
 * 为什么用 record：不可变、字段即文档、Jackson 直接序列化给前端，零样板。
 */
public record ExplainInput(
        Long activityId,
        Long skuId,
        /** 触发指标的中文名，如「库存水位」「流速」 */
        String metric,
        /** 指标实测值（已格式化好的字符串，如 "0.32"） */
        String metricValue,
        /** 触发阈值（如 "0.2"） */
        String threshold,
        /** 动作：REALLOCATE / REPRICE / REFUSE / ASK / ESCALATE / ALLOW */
        String action,
        /** 调拨数量（REALLOCATE 时有值） */
        Integer amount,
        /** 调后售价（REPRICE 时有值） */
        BigDecimal price,
        /** 风险等级中文：低 / 中 / 高 */
        String riskLevel
) {
}
