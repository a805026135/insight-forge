package com.insightforge.rule.engine;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

/**
 * 规则引擎 Worker 入口（Phase 14.1）。
 *
 * 数据面独立进程：消费 {@code sales.tick} → Drools 确定性匹配 → 控盘执行（写回同一份
 * Redis 库存/价表 + DB 审计）。与 api 进程物理隔离，LLM 永不进热路径。
 *
 * 启动方式（Docker）：
 *   SPRING_PROFILES_ACTIVE=integration java -jar insightforge-rule-engine-*.jar
 *   （integration profile 才会装配 RedissonLockService / RedisStockSegmentService /
 *    RedisPriceStore / SalesTickConsumer；默认 profile 跑内存桩仅用于单测。）
 */
@SpringBootApplication(scanBasePackages = {
        "com.insightforge.rule.engine",
        "com.insightforge.persistence",
        "com.insightforge.common"
})
@MapperScan("com.insightforge.persistence.mapper")
public class RuleEngineWorkerApplication {

    public static void main(String[] args) {
        // classpath 带 spring-ai/webflux（agent-core 的 DSL 类型依赖），默认会按 Reactive Web 启动失败；
        // 规则引擎 worker 是纯后台消费者，显式指定非 Web 应用。
        SpringApplication app = new SpringApplication(RuleEngineWorkerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}
