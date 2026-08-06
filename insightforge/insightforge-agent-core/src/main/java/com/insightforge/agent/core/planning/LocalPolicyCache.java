package com.insightforge.agent.core.planning;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内存策略缓存（默认实现，零依赖可跑）。
 * 键 {@code guardrail:dsl:{activityId}} 与 Redis 热更新键一致——Phase 9 接入 Redis 时，
 * 由带 Redis 的 rule-engine 模块把 {@code guardrail_policy} 表镜像到 Redis 即可热更新。
 */
@Component
@Primary
public class LocalPolicyCache implements PolicyCache {

    /** 用约定的 Redis 键格式做内存键，保证两侧语义一致。 */
    private final Map<String, GuardrailPolicy> store = new ConcurrentHashMap<>();

    private static String key(Long activityId) {
        return "guardrail:dsl:" + activityId;
    }

    @Override
    public void put(Long activityId, GuardrailPolicy policy) {
        store.put(key(activityId), policy);
    }

    @Override
    public GuardrailPolicy get(Long activityId) {
        return store.get(key(activityId));
    }

    @Override
    public void evict(Long activityId) {
        store.remove(key(activityId));
    }
}
