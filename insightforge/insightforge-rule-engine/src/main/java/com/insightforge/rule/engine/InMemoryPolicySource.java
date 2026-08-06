package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.GuardrailPolicy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 DSL 来源（测试用，零 DB）。生产换 {@code DbPolicySource}（从 guardrail_policy 表读）。
 *
 * 注意：本类不带 {@code @Component}，由测试配置用 {@code @Bean} 显式注册为唯一的
 * {@link PolicySource}，避免与生产的 DbPolicySource 在按类型注入时产生歧义。
 */
public class InMemoryPolicySource implements PolicySource {

    private final Map<Long, GuardrailPolicy> store = new ConcurrentHashMap<>();

    /** 测试预设某活动的最新 DSL。 */
    public void install(long activityId, GuardrailPolicy policy) {
        store.put(activityId, policy);
    }

    @Override
    public GuardrailPolicy loadLatest(long activityId) {
        return store.get(activityId);
    }
}
