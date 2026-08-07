package com.insightforge.seckill.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 销售心跳事件（集成缝：秒杀扣减成功后发往 sales.tick 主题）。
 *
 * 这是「秒杀数据面 → 规则引擎控制面」的关键接线：每成功扣减一笔，就发一条 tick，
 * 告诉后方的 Agent/规则引擎「这个 SKU 又卖了一笔」。规则引擎消费者据此读取
 * 同一份 Redis 库存快照、跑 Drools、产出调价/调拨工单——从而让
 * 「Agent 控盘与用户抢购争用同一库存」从原型变成真实链路。
 *
 * 事件体刻意保持最小（只带 activityId/skuId/qty/ts）：剩余库存等快照由消费者
 * 直接读共享 Redis 库存键获取，保证规则引擎看到的是「实时同一份库存」。
 *
 * 序列化约定同 {@link OrderCreatedEvent}：无参构造 + getter/setter（@Data）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesTickEvent implements Serializable {

    /** 活动 id */
    private Long activityId;

    /** SKU id（= activity_sku.id） */
    private Long skuId;

    /** 本笔扣减数量 */
    private Integer qty;

    /** 事件时间戳（ms） */
    private Long ts;

    /** 便捷工厂：组装一条 tick（ts 取当前时间） */
    public static SalesTickEvent of(Long activityId, Long skuId, int qty) {
        SalesTickEvent e = new SalesTickEvent();
        e.setActivityId(activityId);
        e.setSkuId(skuId);
        e.setQty(qty);
        e.setTs(System.currentTimeMillis());
        return e;
    }
}
