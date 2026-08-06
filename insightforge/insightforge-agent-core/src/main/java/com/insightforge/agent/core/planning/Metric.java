package com.insightforge.agent.core.planning;

/**
 * 护栏 DSL 可引用的「指标」（大白话：规则盯的是什么数）。
 * 对应设计文档 Phase 8 的 DSL 自编译校验——指标必须在白名单内，否则 DSL 编译失败（Fail-Close）。
 */
public enum Metric {
    STOCK_RATIO,       // 库存水位比例 = 剩余库存 / 总库存（低于阈值→该补货/调拨）
    SALES_VELOCITY,    // 销售速度（件/分钟，骤降→可能要告警）
    PRICE_GAP,         // 当前价与成本/竞品的 Gap（过大→该调价）
    CONVERSION_RATE,  // 转化率（过低→该换选品/文案）
    INVALID            // 兜底：未知指标，编译期直接判非法
}
