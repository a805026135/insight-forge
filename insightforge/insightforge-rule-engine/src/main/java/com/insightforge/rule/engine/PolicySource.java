package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.GuardrailPolicy;

/**
 * DSL 来源抽象（大白话：规则引擎去哪拿最新的「控盘说明书」）。
 *
 * 默认实现 {@code DbPolicySource} 从 {@code guardrail_policy} 表读（Phase 9 生产路径）；
 * 测试用 {@link InMemoryPolicySource}（零 DB）。二者都实现本接口，规则引擎只依赖接口，
 * 不关心 DSL 是来自数据库还是内存——这也是控制面（写 DSL）与数据面（读 DSL 执行）解耦的一环。
 */
public interface PolicySource {

    /**
     * 读某活动最新版 DSL；无则返回 null（规则引擎对 null 返回空指令列表，不报错）。
     */
    GuardrailPolicy loadLatest(long activityId);
}
