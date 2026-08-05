package com.insightforge.seckill.core.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 主题声明（Phase 0 建 Topic；Broker 可达时由 Spring 自动创建）。
 * 命名与 plan.md §4.3 / spec §13 对齐；guardrail.action.failed 为 DLQ。
 */
@Configuration
public class KafkaTopicConfig {

    public static final String ORDER_CREATED = "order.created";
    public static final String SALES_TICK = "sales.tick";
    public static final String GUARDRAIL_ACTION = "guardrail.action";
    public static final String GUARDRAIL_ACTION_FAILED = "guardrail.action.failed";

    @Bean
    public org.apache.kafka.clients.admin.NewTopic orderCreated() {
        return TopicBuilder.name(ORDER_CREATED).partitions(3).replicas(1).build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic salesTick() {
        return TopicBuilder.name(SALES_TICK).partitions(3).replicas(1).build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic guardrailAction() {
        return TopicBuilder.name(GUARDRAIL_ACTION).partitions(3).replicas(1).build();
    }

    @Bean
    public org.apache.kafka.clients.admin.NewTopic guardrailActionFailed() {
        return TopicBuilder.name(GUARDRAIL_ACTION_FAILED).partitions(3).replicas(1).build();
    }
}
