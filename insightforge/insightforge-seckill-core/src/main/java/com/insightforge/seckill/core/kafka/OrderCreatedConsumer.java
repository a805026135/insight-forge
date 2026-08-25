package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.redis.RedisStockService;
import com.insightforge.seckill.core.service.OrderCreationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 订单事件消费者（Phase 4）。
 *
 * 监听 order.created 主题：生产者在用户抢购「扣减成功」后发来的消息，到这里被异步消费——
 * 真正去「建订单 + 把 DB 库存同步减掉」。这样用户的请求路径上就没有 DB 写，
 * 接口能极快返回，海量并发先堆在 Kafka，由本消费者按自己节奏落库（异步削峰）。
 *
 * 为什么不会重复建单（exactly-once）？
 *   1) 生产者用 orderId 作消息 key + 幂等生产者，正常一条消息只来一次；
 *   2) 即便 Kafka 重投（at-least-once 语义）或生产者重试，我们用 orders.order_id 上的
 *      **唯一约束**做最终兜底：第二次插入同一 orderId 会触发 DuplicateKeyException，
 *      捕获后直接忽略（订单早就建好了）——双重保险。
 *   3) 只有「本次真正新建了订单」时才去扣 DB 库存，保证库存也恰好减一次，不会因重投而多扣。
 *
 * 仅「非 local」生效：local 下没有 Kafka，本监听器不创建。
 */
@Component
@Profile("!local")
public class OrderCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedConsumer.class);

    private final ObjectMapper objectMapper;
    private final ObjectProvider<RedisStockService> redisStockService;
    private final OrderCreationService orderCreationService;

    public OrderCreatedConsumer(ObjectMapper objectMapper,
                                OrderCreationService orderCreationService,
                                ObjectProvider<RedisStockService> redisStockService) {
        this.objectMapper = objectMapper;
        this.orderCreationService = orderCreationService;
        this.redisStockService = redisStockService;
    }

    // 并发数默认等于 order.created 的 3 个分区。每个分区只会被组内一个线程消费，
    // 因此提高到 3 才能真正利用已创建的分区；生产环境按 DB 连接池和写入能力调节，不能盲目加大。
    @KafkaListener(topics = KafkaTopicConfig.ORDER_CREATED,
            groupId = "insightforge-order-consumer",
            concurrency = "${insightforge.seckill.order-consumer-concurrency:3}")
    @Transactional
    public void onOrderCreated(ConsumerRecord<String, String> record) {
        OrderCreatedEvent evt;
        try {
            evt = objectMapper.readValue(record.value(), OrderCreatedEvent.class);
        } catch (Exception e) {
            // 消息体损坏：记录并放弃（不阻塞后续消息）。生产里可转发 DLQ（guardrail.action.failed）。
            log.error("订单事件反序列化失败，已跳过：{}", record.value(), e);
            return;
        }
        // Phase 14.5：链路 traceId 贯穿（来自生产端 MDC，随消息透传）
        MDC.put("traceId", evt.getTraceId());
        try {
            process(evt);
            acknowledgeAfterCommit(evt.getOrderId());
        } finally {
            MDC.remove("traceId");
        }
    }

    private void process(OrderCreatedEvent evt) {
        // 用和生产者一致的确定性订单号，确保「同一抢购」在 DB 里只有一行
        SeckillRequest req = new SeckillRequest();
        req.setUserId(evt.getUserId());
        req.setActivityId(evt.getActivityId());
        req.setSkuId(evt.getSkuId());
        req.setQty(evt.getQty());

        // —— 异步建单（幂等：order_id 唯一约束兜底）——
        boolean inserted = orderCreationService.createOrderIdempotent(
                req, evt.getOrderId(), evt.getPrice(), evt.getProductId());

        // 成功逐单 INFO 会与请求线程争抢 stdout/json-file 锁；成功路径由指标统计，
        // 仅异常保留高等级日志。需要逐单排障时可临时打开 DEBUG。
        log.debug("异步建单完成：orderId={}, inserted={}", evt.getOrderId(), inserted);
    }

    /** DB 提交后才删除恢复凭证，避免“先 ACK、后回滚”造成永久丢单。 */
    private void acknowledgeAfterCommit(String orderId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                RedisStockService stockService = redisStockService.getIfAvailable();
                if (stockService == null) {
                    return;
                }
                try {
                    stockService.acknowledgeReservation(orderId);
                } catch (RuntimeException e) {
                    log.warn("订单已提交但 Reservation ACK 失败，交给补偿器清理：orderId={}", orderId, e);
                }
            }
        });
    }
}
