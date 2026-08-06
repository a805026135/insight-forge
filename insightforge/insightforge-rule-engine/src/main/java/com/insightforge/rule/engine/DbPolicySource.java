package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.persistence.entity.GuardrailPolicyEntity;
import com.insightforge.persistence.mapper.GuardrailPolicyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产 DSL 来源：从 guardrail_policy 表读最新版（Phase 8 控制面写入，Phase 9 数据面读取）。
 *
 * 带本地缓存（ConcurrentHashMap）：DSL 不变就不查库；版本变化需调 {@link #invalidate(long)} 热更新。
 * 注意：本类不带 @Component，由生产配置 {@code RuleEngineConfig} 用 @Bean 显式注册，
 * 以便测试环境用 InMemoryPolicySource 替换（避免引入数据库）。
 */
public class DbPolicySource implements PolicySource {

    private final GuardrailPolicyMapper mapper;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public DbPolicySource(GuardrailPolicyMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public GuardrailPolicy loadLatest(long activityId) {
        GuardrailPolicyEntity e = mapper.selectLatestByActivity(activityId);
        if (e == null) {
            return null;
        }
        CacheEntry cached = cache.get(activityId);
        if (cached != null && cached.version().equals(e.getVersion())) {
            return cached.policy();
        }
        try {
            GuardrailPolicy p = objectMapper.readValue(e.getPolicyJson(), GuardrailPolicy.class);
            cache.put(activityId, new CacheEntry(e.getVersion(), p));
            return p;
        } catch (Exception ex) {
            throw new IllegalStateException("DSL JSON 反序列化失败 activityId=" + activityId, ex);
        }
    }

    /** DSL 发布新版本后调用，让本地缓存失效，下次读库拿最新版。 */
    public void invalidate(long activityId) {
        cache.remove(activityId);
    }

    private record CacheEntry(String version, GuardrailPolicy policy) {
    }
}
