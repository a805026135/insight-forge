package com.insightforge.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单（Phase 1 建表；Phase 4 改为幂等订单号）。
 *
 * orderId：订单号。Phase 4 起由 {@code SeckillService.buildOrderId} 确定性推导为
 *          {@code "OF-{activityId}-{skuId}-{userId}"}，而非随机 UUID。
 *          这样「同一用户 + 同一活动 + 同一 SKU」永远算出同一个订单号，配合
 *          orders 表上的 uk_order_id 唯一约束（见 schema.sql），实现 exactly-once
 *          幂等：无论用户重复点击、网络重试，还是 Kafka 重投，都只生成 1 张单。
 *          非 local 路径还在 Redis 用 order:idemp:{orderId} 做 SETNX 前置闸门。
 * status：Phase 1 写 "CREATED"，Phase 6 升级成订单状态机
 *         （PRE_CHECK → LOCK_STOCK → CREATE_ORDER → PAY → DONE / CANCEL）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("orders")
public class Orders extends BaseEntity {

    /** 订单号（Phase 4 改为幂等 hash） */
    private String orderId;

    /** 用户 id */
    private Long userId;

    /** 活动 id */
    private Long activityId;

    /** SKU id（= product.id） */
    private Long skuId;

    /** 购买数量 */
    private Integer qty;

    /** 成交单价快照（下单瞬间的实时价；AI 调价后新订单带新价，用于 GMV 与对账） */
    private BigDecimal price;

    /** 订单状态（Phase 6 状态机） */
    private String status;

    /** 下单时间 */
    private LocalDateTime createdAt;
}
