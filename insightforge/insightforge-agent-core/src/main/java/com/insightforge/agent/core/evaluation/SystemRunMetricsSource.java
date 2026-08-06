package com.insightforge.agent.core.evaluation;

/**
 * 系统运行指标数据源（大白话：Agent 跑了多久、多少次、有多少「正确拒绝」）。
 *
 * 同理 BusinessMetricsSource：接口隔离，生产换成读 agent_trace 的真实聚合
 * （ObservabilityService.correctFailureRate() 已在做这件事），本地用种子。
 */
public interface SystemRunMetricsSource {

    SystemRunMetrics metrics();
}
