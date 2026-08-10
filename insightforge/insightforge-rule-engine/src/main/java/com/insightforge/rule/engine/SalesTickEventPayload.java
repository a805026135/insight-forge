package com.insightforge.rule.engine;

import lombok.Data;

import java.io.Serializable;

/**
 * 销售心跳消息体（消费者侧反序列化用）。
 *
 * 字段与 seckill-core 的 {@code SalesTickEvent} 一一对应（activityId/skuId/qty/ts）。
 * 规则引擎模块刻意不依赖 seckill-core（保持控制面/数据面解耦），所以用本地 POJO 接收，
 * 再据此读取「同一份 Redis 库存」构造成 Drools 的 {@link SalesTick} 事实。
 *
 * 无参构造 + getter/setter（@Data）保证 Jackson 可反序列化。
 */
@Data
public class SalesTickEventPayload implements Serializable {

    private Long activityId;
    private Long skuId;
    private Integer qty;
    private Long ts;
    private String traceId;
}
