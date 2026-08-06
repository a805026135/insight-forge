package com.insightforge.agent.core.evaluation;

/**
 * 经营指标数据源（大白话：A/B 两边「经营结果」从哪来）。
 *
 * 做成接口是为了「控制面 / 数据面分离」：评估闭环（控制面）不关心数据怎么采，
 * 只认 BusinessMetrics。生产环境把这里换成读 orders / activity_sku 的真实实现
 * （api 模块的 ObservabilityService 已在读同样的表）；本地 / CI 用确定性种子实现，
 * 保证评估门槛可复现、可单测。本沙箱即使用 InMemory 种子。
 */
public interface BusinessMetricsSource {

    /** 对照组：人工规则对应的经营结果。 */
    BusinessMetrics manualRuleMetrics();

    /** 实验组：AI 控盘对应的经营结果。 */
    BusinessMetrics aiControlledMetrics();
}
