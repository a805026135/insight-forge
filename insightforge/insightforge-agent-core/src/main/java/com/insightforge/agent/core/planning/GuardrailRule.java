package com.insightforge.agent.core.planning;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 一条护栏规则（DSL 的原子单元）。
 * 大白话：当「指标 metric」用「算子 operator」比「阈值 threshold」时，就执行「动作 action」，动作参数放 params。
 *
 * 例：当 STOCK_RATIO(库存比例) LT(小于) 0.2 时，REALLOCATE(调拨) 50 件到热段。
 */
public record GuardrailRule(
        String id,
        Metric metric,
        Operator operator,
        BigDecimal threshold,
        Action action,
        Map<String, Object> params
) {
}
