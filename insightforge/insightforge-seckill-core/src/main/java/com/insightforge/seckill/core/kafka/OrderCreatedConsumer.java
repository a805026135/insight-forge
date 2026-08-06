package com.insightforge.seckill.core.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.dto.SeckillRequest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    private final OrdersMapper ordersMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final ObjectMapper objectMapper;

    public OrderCreatedConsumer(OrdersMapper ordersMapper,
                                ActivitySkuMapper activitySkuMapper,
                                ObjectMapper objectMapper) {
        this.ordersMapper = ordersMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopicConfig.ORDER_CREATED, groupId = "insightforge-order-consumer")
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

        // 用和生产者一致的确定性订单号，确保「同一抢购」在 DB 里只有一行
        SeckillRequest req = new SeckillRequest();
        req.setUserId(evt.getUserId());
        req.setActivityId(evt.getActivityId());
        req.setSkuId(evt.getSkuId());
        req.setQty(evt.getQty());

        // —— 异步建单（幂等：order_id 唯一约束兜底）——
        boolean inserted = createOrderIdempotent(req, evt.getOrderId());

        // —— 仅在「本次真正新建订单」时才把 DB 库存同步减掉（最终一致：Redis 已先扣，这里补齐 DB）——
        // decrementStock 是原子条件更新，多实例并发也安全，绝不为负；且仅建单成功才扣，杜绝重投多扣。
        if (inserted) {
            activitySkuMapper.decrementStock(evt.getSkuId(), evt.getQty());
        }

        log.info("异步建单完成：orderId={}, inserted={}", evt.getOrderId(), inserted);
    }

    /** 建单并吞掉「重复建单」异常（幂等核心）；返回是否真正插入了新行。 */
    private boolean createOrderIdempotent(SeckillRequest req, String orderId) {
        Orders order = new Orders();
        order.setOrderId(orderId);
        order.setUserId(req.getUserId());
        order.setActivityId(req.getActivityId());
        order.setSkuId(req.getSkuId());
        order.setQty(req.getQty());
        order.setStatus("CREATED");
        order.setCreatedAt(LocalDateTime.now());
        try {
            ordersMapper.insert(order);
            return true;
        } catch (DuplicateKeyException e) {
            // 同一 orderId 已经建过单（Kafka 重投 / 生产者重试）：幂等忽略
            log.info("订单已存在，命中幂等去重：orderId={}", orderId);
            return false;
        }
    }
}
