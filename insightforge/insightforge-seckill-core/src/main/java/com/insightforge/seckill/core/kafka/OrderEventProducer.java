package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;


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

    private static final Logger log = LoggerFactory.getLogger(OrderEventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final boolean waitForBrokerAck;
    private final ObjectMapper objectMapper;

    public OrderEventProducer(KafkaTemplate<String, String> kafkaTemplate,
                              ObjectMapper objectMapper,
                              @Value("${insightforge.seckill.kafka-publish-mode:async-reservation}") String publishMode) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.waitForBrokerAck = "sync-ack".equalsIgnoreCase(publishMode);
    }

    /**
     * 发送「订单已扣减、待建单」事件。
     *
     * @return true 表示消息已被本地 Kafka producer 接受；false 表示序列化或本地入队
     *         同步失败，Reservation 仍由补偿器收敛。
     *
     * Phase 21：不在请求线程等待 broker ACK。可靠性边界已经前移到 Redis Reservation：
     * Lua 在扣库存时原子保存完整订单载荷和 pending ZSET，即使异步 ACK 最终失败，补偿器也能
     * 重放建单。因此请求线程只负责入队，broker ACK 在回调里观测；这既保留 acks=all、幂等
     * producer 的可靠性，又去掉每单一次网络往返造成的 QPS 上限。
     */
    public boolean sendOrderCreated(OrderCreatedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            // key = orderId：同订单同分区，保序 + 便于消费端幂等
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(KafkaTopicConfig.ORDER_CREATED, event.getOrderId(), payload);
            var sendFuture = kafkaTemplate.send(record);
            if (waitForBrokerAck) {
                sendFuture.get(2, TimeUnit.SECONDS);
            } else {
                sendFuture.whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("order.created 异步 ACK 失败，等待 Reservation 补偿：orderId={}, err={}",
                                event.getOrderId(), error.getMessage());
                    }
                });
            }
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("order.created 本地入队失败，保留 Reservation 等待补偿：orderId={}, err={}",
                    event.getOrderId(), e.getMessage());
            return false;
        }
    }
}
