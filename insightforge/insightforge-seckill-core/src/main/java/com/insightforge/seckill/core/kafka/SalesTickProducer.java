package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.seckill.core.dto.SalesTickEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 销售心跳生产者（集成缝：秒杀 → sales.tick）。
 *
 * 与 {@link OrderEventProducer} 同构：在「库存扣减成功」之后调用 {@code send()}，
 * 把 tick 丢进 Kafka 的 sales.tick 主题，由 rule-engine 的消费者异步驱动规则引擎。
 *
 * 仅「非 local」生效：local 下 Kafka 自动配置被排除、KafkaTemplate 不存在，本 Bean 不创建
 * （{@code @Profile("!local")}），因此零依赖冒烟路径完全不受影响。
 *
 * 可靠性同 order.created：依赖 application.yml 的 acks=all + 生产者幂等（见 OrderEventProducer 说明）。
 * 这里同样 fire-and-forget、不阻塞用户接口；消息 key = activityId:skuId，保序且便于消费端按 key 处理。
 */
@Component
@Profile("!local")
public class SalesTickProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public SalesTickProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送一条销售心跳。
     *
     * @return true=已成功发出（broker 已 ack）；false=序列化/发送失败（调用方可忽略，tick 丢失不阻塞抢购）。
     */
    public boolean send(SalesTickEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // key = activityId:skuId：同 SKU 同分区，保序 + 便于消费端按 key 串行处理
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(KafkaTopicConfig.SALES_TICK,
                            event.getActivityId() + ":" + event.getSkuId(), payload);
            kafkaTemplate.send(record);
            return true;
        } catch (JsonProcessingException | RuntimeException e) {
            // sales.tick only drives auxiliary rule evaluation. A synchronous Kafka
            // metadata/buffer failure must never turn an accepted order into HTTP 500.
            // Order durability is owned independently by order.created + reservations.
            return false;
        }
    }
}
