package com.insightforge.rule.engine;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * 规则引擎测试装配（零 DB）。
 *
 * 复用生产同款 @ComponentScan 装配所有规则引擎 bean，但用 InMemoryPolicySource 替换
 * DbPolicySource（不连数据库即可跑规则引擎逻辑）。测试通过 @Autowired InMemoryPolicySource
 * 预设 DSL 后即可驱动 RuleEngineService。
 *
 * 关键：必须排除生产装配类 RuleEngineConfig —— 它也在本包内，会被 @ComponentScan 顺带扫到，
 * 而其 @Bean dbPolicySource 依赖 GuardrailPolicyMapper（MyBatis，需数据库），在零 DB 测试上下文
 * 中无法满足，会导致整个 ApplicationContext 加载失败（被 Spring 的 failure-threshold 机制吞掉真实根因）。
 */
@Configuration
@ComponentScan(
        basePackages = "com.insightforge.rule.engine",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = RuleEngineConfig.class
        )
)
public class RuleEngineTestConfig {

    @Bean
    public PolicySource inMemoryPolicySource() {
        return new InMemoryPolicySource();
    }
}
