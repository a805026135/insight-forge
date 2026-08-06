package com.insightforge.agent.core.planning;

/**
 * 护栏策略缓存抽象（大白话：发布后的 DSL 放哪，在线规则引擎（Phase 9）好秒级热更新）。
 *
 * 键约定：{@code guardrail:dsl:{activityId}}——与 Phase 9 规则引擎 Worker（它带 Redis）的
 * 热更新键完全一致；agent-core 默认用本地内存实现跑通整个链路，Redis 实现在 Phase 9 接入。
 */
public interface PolicyCache {
    void put(Long activityId, GuardrailPolicy policy);

    GuardrailPolicy get(Long activityId);

    void evict(Long activityId);
}
