package com.insightforge.agent.core.evaluation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评估闭环的 Bean 装配（大白话：把「及格线」这个配置登记成 Spring Bean）。
 *
 * 只有 EvaluationThresholds 需要在这里显式成 Bean（它是 record，无默认无参构造不合适做 @Component）。
 * 其余数据源 / 评估器都是无状态 @Component，已被 AgentCoreTestConfig / 组件扫描自动装载。
 */
@Configuration
public class EvaluationConfig {

    @Bean
    public EvaluationThresholds evaluationThresholds() {
        return EvaluationThresholds.defaults();
    }
}
