package com.insightforge.rule.engine;

import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * 规则引擎测试装配（零 DB、零中间件）。
 *
 * 复用生产同款 @ComponentScan 装配所有规则引擎 bean，但用 InMemoryPolicySource 替换
 * DbPolicySource（不连数据库即可跑规则引擎逻辑）。测试通过 @Autowired InMemoryPolicySource
 * 预设 DSL 后即可驱动 RuleEngineService。
 *
 * 关键：必须排除生产装配类 RuleEngineConfig —— 它也在本包内，会被 @ComponentScan 顺带扫到，
 * 而其 @Bean dbPolicySource 依赖 GuardrailPolicyMapper（MyBatis，需数据库），在零 DB 测试上下文
 * 中无法满足，会导致整个 ApplicationContext 加载失败（被 Spring 的 failure-threshold 机制吞掉真实根因）。
 *
 * 集成缝（M7）补充：本模块新引入了 data-redis / redisson / spring-kafka 依赖。沙箱零中间件，
 * 必须排除它们的自动配置，否则会尝试连接不存在的 Redis/Redisson/Kafka 而让测试上下文加载失败。
 * 排除后，@Profile("integration") 的真实集成 Bean（RedissonLockService / RedisStockSegmentService /
 * SalesTickConsumer）不会被创建，退回 @Profile("!integration") 的内存默认实现，22 测试仍绿。
 */
@Configuration
@EnableAutoConfiguration(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        KafkaAutoConfiguration.class
})
@ComponentScan(
        basePackages = "com.insightforge.rule.engine",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = { RuleEngineConfig.class, RuleEngineWorkerApplication.class }
        )
)
public class RuleEngineTestConfig {

    @Bean
    public PolicySource inMemoryPolicySource() {
        return new InMemoryPolicySource();
    }
}
