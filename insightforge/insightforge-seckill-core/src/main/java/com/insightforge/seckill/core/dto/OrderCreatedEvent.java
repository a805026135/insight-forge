package com.insightforge.seckill.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.io.Serializable;

/**
 * 订单创建事件（Phase 4 的 Kafka 消息体）。
 *
 * 抢购接口在「库存扣减成功」后，立刻把这条事件丢进 Kafka 的 order.created 主题，
 * 由消费者 {@code OrderCreatedConsumer} 异步建单落库。事件里带着**确定性订单号**
 * orderId，它是「同一用户 + 同一活动 + 同一 SKU」的唯一指纹——无论这条消息被重发几次，
 * 最终只会生成一张订单（exactly-once）。
 *
 * 必须能被 Jackson 序列化 / 反序列化，所以要有：无参构造 + getter/setter（@Data 提供）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent implements Serializable {

    /** 幂等订单号：由 (activityId, skuId, userId) 确定性推导，见 SeckillService.buildOrderId */
    private String orderId;

    /** 用户 id */
    private Long userId;

    /** 活动 id */
    private Long activityId;

    /** SKU id（= activity_sku.id，本项目里请求里的 skuId 即为活动库存表主键） */
    private Long skuId;

    /** 购买数量 */
    private Integer qty;

    /** 成交单价快照（REPRICE 生效后，新订单以实时价成交，供 GMV 统计） */
    private BigDecimal price;

    /** 链路 traceId（来自请求 MDC，随消息透传，消费端日志可串联） */
    private String traceId;

    /** 便捷工厂：从抢购请求 + 订单号组装一条事件 */
    public static OrderCreatedEvent from(SeckillRequest req, String orderId, BigDecimal price) {
        OrderCreatedEvent e = new OrderCreatedEvent();
        e.setOrderId(orderId);
        e.setUserId(req.getUserId());
        e.setActivityId(req.getActivityId());
        e.setSkuId(req.getSkuId());
        e.setQty(req.getQty());
        e.setPrice(price);
        return e;
    }
}
