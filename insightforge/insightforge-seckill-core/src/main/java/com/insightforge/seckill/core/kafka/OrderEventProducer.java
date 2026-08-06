package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 订单事件生产者（Phase 4）。
 *
 * 抢购接口在「库存扣减成功」之后，调用本类的 {@code sendOrderCreated()} 往 Kafka 的
 * order.created 主题发一条消息，然后**立刻返回用户**——把「建单落库」这件相对慢的事
 * 甩给后方的消费者异步去做。这就是「异步削峰」：用户的请求路径上不再有 DB 写，
 * 接口能在 ~毫秒级返回，海量并发先堆在 Kafka 里，由消费者按自己节奏消化。
 *
 * 生产者可靠性（防丢 / 防重）：
 *   - acks=all：必须所有 in-sync 副本都收到消息才算成功（配置在 application.yml）。
 *   - retries + enable.idempotence=true：网闪重发时由 broker 去重，不会重复写入。
 *   - 消息 key = orderId：保证同一订单的消息落到同一分区，既保序又方便消费者按 key 幂等。
 *
 * 仅「非 local」生效：local 下 Kafka 自动配置被排除、KafkaTemplate 不存在，本 Bean 不创建。
 */
@Component
@Profile("!local")
public class OrderEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OrderEventProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 发送「订单已扣减、待建单」事件。
     *
     * @return true 表示消息已成功发出（broker 已 ack）；false 表示发送失败（调用方应降级同步建单）。
     *         这里采用 fire-and-forget：不阻塞等待，保证接口极速返回；消息持久性由
     *         acks=all + 生产者重试保证，最终一致由 Phase 6 对账兜底。
     */
    public boolean sendOrderCreated(OrderCreatedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // key = orderId：同订单同分区，保序 + 便于消费端幂等
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(KafkaTopicConfig.ORDER_CREATED, event.getOrderId(), payload);
            kafkaTemplate.send(record);
            return true;
        } catch (JsonProcessingException e) {
            // JSON 序列化不该失败（字段都是基础类型），真失败了只能交给调用方降级
            return false;
        }
    }
}
